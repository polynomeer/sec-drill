package secdrill.controlplane.evidence

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.kernel.EvidenceSource
import secdrill.kernel.TrustLevel
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Evidence Ledger invariants (10, 14, 22; prompt 05 required tests). */
@IntegrationTest
class EvidenceLedgerTest {
    @Autowired private lateinit var ledger: LedgerAppender
    @Autowired private lateinit var verifier: LedgerVerifier
    @Autowired private lateinit var artifacts: ArtifactService
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var dataSource: JdbcConnectionDetails

    private fun session(): UUID = fixtures.activeSession(fixtures.learner().userId)

    private fun append(
        session: UUID,
        payload: Map<String, Any?> = mapOf("n" to 1),
        source: EvidenceSource = EvidenceSource.CONTROL,
        trust: TrustLevel = TrustLevel.SERVER_VERIFIED,
        occurredAt: Instant? = null,
        eventId: UUID? = null,
        artifactId: UUID? = null,
    ) = transactions.execute { ledger.append(session, "TEST_RESULT", source, trust, payload, occurredAt, eventId, artifactId) }!!

    /** Runs raw SQL as a superuser with the append-only triggers off, as an attacker with DB control would. */
    private fun tamper(sql: String, vararg args: Any) = DriverManager.getConnection(dataSource.jdbcUrl, dataSource.username, dataSource.password).use { c ->
        c.createStatement().execute("ALTER TABLE evidence DISABLE TRIGGER USER")
        try {
            c.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.executeUpdate() }
        } finally {
            c.createStatement().execute("ALTER TABLE evidence ENABLE TRIGGER USER")
        }
    }

    private fun sqlState(sql: String, role: String? = null, vararg args: Any): String? =
        DriverManager.getConnection(dataSource.jdbcUrl, dataSource.username, dataSource.password).use { c ->
            role?.let { c.createStatement().execute("SET ROLE $it") }
            runCatching { c.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.execute() } }
                .exceptionOrNull()?.let { (it as SQLException).sqlState }
        }

    @Test
    fun `concurrent appends get consecutive seq values and a valid chain`() {
        val session = session()
        val pool = Executors.newFixedThreadPool(8)
        try {
            pool.invokeAll((1..24).map { n -> Callable { append(session, mapOf("n" to n)) } }).forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        val seqs = jdbc.sql("SELECT seq FROM evidence WHERE session_id = ? ORDER BY seq").param(session).query(Long::class.java).list()
        assertEquals((1L..24L).toList(), seqs)
        val verification = verifier.verify(session)
        assertTrue(verification.intact, verification.problems.toString())
        assertEquals(24L, verification.lastSeq)
    }

    @Test
    fun `the same source event is recorded once`() {
        val session = session()
        val event = UUID.randomUUID()
        val first = append(session, eventId = event)
        val again = append(session, eventId = event)
        assertEquals(first.evidenceId, again.evidenceId)
        assertTrue(again.duplicate)
        assertEquals(1, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ?", session))
    }

    @Test
    fun `late observations keep their time while seq keeps server order`() {
        val session = session()
        append(session, mapOf("n" to 1), occurredAt = Instant.parse("2026-10-04T00:00:10Z"))
        append(session, mapOf("n" to 2), source = EvidenceSource.COLLECTOR, trust = TrustLevel.OBSERVED, occurredAt = Instant.parse("2026-10-04T00:00:05Z"))
        val order = jdbc.sql("SELECT occurred_at::text FROM evidence WHERE session_id = ? ORDER BY seq").param(session).query(String::class.java).list()
        assertTrue(order[0]!! > order[1]!!, "seq order is not time order: $order")
        assertTrue(verifier.verify(session).intact)
    }

    @Test
    fun `verifier detects edited, removed and truncated entries`() {
        val edited = session().also { s -> repeat(3) { append(s, mapOf("n" to it)) } }
        tamper("UPDATE evidence SET safe_payload = '{\"n\": 99}' WHERE session_id = ? AND seq = 2", edited)
        assertTrue(verifier.verify(edited).problems.any { it.seq == 2L && "payload" in it.reason })

        val removed = session().also { s -> repeat(3) { append(s, mapOf("n" to it)) } }
        tamper("DELETE FROM evidence WHERE session_id = ? AND seq = 2", removed)
        val gap = verifier.verify(removed)
        assertTrue(gap.problems.any { "gap" in it.reason } && gap.problems.any { "link" in it.reason }, gap.problems.toString())

        val truncated = session().also { s -> repeat(3) { append(s, mapOf("n" to it)) } }
        tamper("DELETE FROM evidence WHERE session_id = ? AND seq = 3", truncated)
        assertTrue(verifier.verify(truncated).problems.any { "head" in it.reason })
    }

    @Test
    fun `official results and learner claims never share a trust level`() {
        val session = session()
        assertFailsWith<IllegalArgumentException> { append(session, source = EvidenceSource.USER, trust = TrustLevel.SERVER_VERIFIED) }
        assertFailsWith<IllegalArgumentException> { append(session, source = EvidenceSource.CONTROL, trust = TrustLevel.USER_REPORTED) }
        append(session, source = EvidenceSource.USER, trust = TrustLevel.USER_REPORTED)
        // The database enforces the same rule even if the application check is bypassed.
        val digest = "a".repeat(64)
        assertEquals("23514", sqlState(
            """INSERT INTO evidence(id, session_id, seq, event_type, source, trust_level, schema_version, occurred_at, payload_digest, previous_hash, hash)
               VALUES (?, ?, 99, 'HYPOTHESIS_REPORTED', 'USER', 'SERVER_VERIFIED', 1, now(), ?, ?, ?)""", null, UUID.randomUUID(), session, digest, digest, digest))
    }

    @Test
    fun `raw secrets and content are refused in ledger payloads`() {
        val session = session()
        listOf(mapOf("flag" to "x"), mapOf("nested" to mapOf("token" to "x")), mapOf("source" to "print(1)"), mapOf("note" to "x".repeat(600)))
            .forEach { payload -> assertFailsWith<IllegalArgumentException>("$payload") { append(session, payload) } }
    }

    @Test
    fun `evidence cannot reference another session's artifact`() {
        val owner = session()
        val other = session()
        val artifact = transactions.execute { artifacts.store(owner, secdrill.kernel.ArtifactSensitivity.RAW_LOG, "text/plain", "synthetic log".toByteArray()) }!!
        val error = assertFailsWith<Exception> { append(other, artifactId = artifact.id) }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { (it as? SQLException)?.sqlState == "23503" }, error.toString())
        append(owner, mapOf("artifactDigest" to artifact.digest), artifactId = artifact.id)
        assertTrue(verifier.verify(owner).intact)
    }

    @Test
    fun `the runtime role cannot change evidence, audit, evaluations or tombstones`() {
        val session = session()
        append(session)
        assertEquals("42501", sqlState("UPDATE evidence SET event_type = 'x' WHERE session_id = ?", "control_app", session))
        assertEquals("42501", sqlState("DELETE FROM evidence WHERE session_id = ?", "control_app", session))
        assertEquals("42501", sqlState("DELETE FROM audit_events", "control_app"))
        assertEquals("42501", sqlState("DELETE FROM evaluations", "control_app"))
        assertEquals("42501", sqlState("DELETE FROM deletion_tombstones", "control_app"))
        assertEquals("42501", sqlState("ALTER TABLE evidence DISABLE TRIGGER USER", "control_app"))
        assertEquals(null, sqlState("SELECT count(*) FROM evidence WHERE session_id = ?", "control_app", session))
    }

    @Test
    fun `deletion contract rows are internally consistent`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val owner = learner.userId.value
        val columns = "INSERT INTO deletion_requests(id, owner_id, scope, session_id, status, requested_at, decided_at, decided_by, completed_at, receipt_digest)"
        val operator = UUID.randomUUID()
        assertEquals("23514", sqlState("$columns VALUES (?, ?, 'SESSION', NULL, 'REQUESTED', now(), NULL, NULL, NULL, NULL)", null, UUID.randomUUID(), owner),
            "SESSION scope needs a session")
        assertEquals(null, sqlState("$columns VALUES (?, ?, 'SESSION', ?, 'REQUESTED', now(), NULL, NULL, NULL, NULL)", null, UUID.randomUUID(), owner, session))
        assertEquals("23514", sqlState("$columns VALUES (?, ?, 'ACCOUNT', NULL, 'COMPLETED', now(), now(), ?, now(), NULL)", null, UUID.randomUUID(), owner, operator),
            "completion needs a receipt")
        assertEquals("23514", sqlState("$columns VALUES (?, ?, 'ACCOUNT', NULL, 'APPROVED', now(), NULL, NULL, NULL, NULL)", null, UUID.randomUUID(), owner),
            "approval needs a decision record")
        assertEquals("23503", sqlState("$columns VALUES (?, ?, 'SESSION', ?, 'REQUESTED', now(), NULL, NULL, NULL, NULL)", null, UUID.randomUUID(), fixtures.learner().userId.value, session),
            "a request cannot target another owner's session")
    }
}
