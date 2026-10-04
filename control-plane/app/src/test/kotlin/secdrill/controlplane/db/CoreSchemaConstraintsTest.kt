package secdrill.controlplane.db

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import secdrill.controlplane.support.TestPostgres
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Applies V1 to a real PostgreSQL and checks that the database itself enforces the core invariants
 * (14, D-03). Each test uses fresh UUIDs, so tests do not depend on each other.
 */
@Testcontainers
class CoreSchemaConstraintsTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = TestPostgres.container()

        @BeforeAll
        @JvmStatic
        fun migrate() = TestPostgres.migrate(postgres)
    }

    private val digest = "a".repeat(64)

    private fun <T> db(block: Connection.() -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { it.block() }

    private fun Connection.exec(sql: String, vararg args: Any?) {
        prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    private fun assertSqlState(expected: String, block: Connection.() -> Unit) {
        val error = runCatching { db(block) }.exceptionOrNull()
        val state = (error as? SQLException)?.sqlState
        assertEquals(expected, state, "expected SQLSTATE $expected but got ${error ?: "no error"}")
    }

    private fun Connection.user(): UUID = UUID.randomUUID().also { exec("INSERT INTO users(id, pseudonym) VALUES (?, 'synthetic')", it) }

    private fun Connection.scenarioVersion(): UUID {
        val scenario = UUID.randomUUID()
        val version = UUID.randomUUID()
        exec("INSERT INTO scenarios(id, slug, title) VALUES (?, ?, 'Synthetic')", scenario, "s-$scenario")
        exec(
            """INSERT INTO scenario_versions(id, scenario_id, version_no, status, content_digest, oracle_digest, oracle_key,
               rubric_version, engine_version, randomization_version, public_manifest)
               VALUES (?, ?, 1, 'DRAFT', ?, ?, 'oracle/synthetic', 'r1', 'e1', 'x1', '{}')""",
            version, scenario, digest, digest,
        )
        return version
    }

    private fun Connection.session(owner: UUID, version: UUID, parent: UUID? = null, mode: String = "CTF"): UUID =
        UUID.randomUUID().also {
            exec(
                """INSERT INTO sessions(id, owner_id, scenario_version_id, parent_session_id, mode, status, phase, seed,
                   rubric_version, engine_version, randomization_version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 'ANALYZE', '\x01', 'r1', 'e1', 'x1')""",
                it, owner, version, parent, mode,
            )
        }

    private fun Connection.lab(session: UUID, owner: UUID, generation: Int, state: String = "READY", cleaned: Boolean = false): UUID =
        UUID.randomUUID().also {
            exec(
                """INSERT INTO labs(id, session_id, owner_id, generation, state, expires_at, cleanup_confirmed_at)
                   VALUES (?, ?, ?, ?, ?, now() + interval '1 hour', ${if (cleaned) "now()" else "NULL"})""",
                it, session, owner, generation, state,
            )
        }

    private fun Connection.submission(session: UUID, clientRequestId: UUID = UUID.randomUUID()): UUID =
        UUID.randomUUID().also {
            exec(
                "INSERT INTO submissions(id, session_id, kind, client_request_id, request_digest, status) VALUES (?, ?, 'FLAG', ?, ?, 'ACCEPTED')",
                it, session, clientRequestId, digest,
            )
        }

    private fun Connection.fixture(): Triple<UUID, UUID, UUID> {
        val owner = user()
        val version = scenarioVersion()
        return Triple(owner, version, session(owner, version))
    }

    @Test
    fun `same client request id cannot create a second submission`() {
        val (_, _, session) = db { fixture() }
        val key = UUID.randomUUID()
        db { submission(session, key) }
        assertSqlState("23505") { submission(session, key) }
    }

    @Test
    fun `parent session must belong to the same owner`() {
        val (_, version, othersSession) = db { fixture() }
        val owner = db { user() }
        assertSqlState("23503") { session(owner, version, parent = othersSession) }
        db { session(owner, version, parent = session(owner, version)) }
    }

    @Test
    fun `lab must belong to the session owner`() {
        val (_, _, session) = db { fixture() }
        val stranger = db { user() }
        assertSqlState("23503") { lab(session, stranger, 1) }
    }

    @Test
    fun `owner has at most one lab until cleanup is confirmed`() {
        val (owner, _, session) = db { fixture() }
        val first = db { lab(session, owner, 1) }
        assertSqlState("23505") { lab(session, owner, 2) }
        db { exec("UPDATE labs SET state = 'TERMINATED', cleanup_confirmed_at = now() WHERE id = ?", first) }
        db { lab(session, owner, 2) }
    }

    @Test
    fun `failed lab without residual resources frees the owner slot`() {
        val (owner, _, session) = db { fixture() }
        db { lab(session, owner, 1, state = "FAILED", cleaned = true) }
        db { lab(session, owner, 2) }
    }

    @Test
    fun `terminated lab requires confirmed cleanup`() {
        val (owner, _, session) = db { fixture() }
        assertSqlState("23514") { lab(session, owner, 1, state = "TERMINATED") }
    }

    @Test
    fun `job target must match its kind and duplicates are rejected`() {
        val (owner, _, session) = db { fixture() }
        val lab = db { lab(session, owner, 1) }
        val submission = db { submission(session) }
        val insertJob = "INSERT INTO jobs(id, submission_id, lab_id, session_id, kind, state) VALUES (?, ?, ?, ?, ?, 'PENDING')"
        assertSqlState("23514") { exec(insertJob, UUID.randomUUID(), null, null, session, "PROVISION") }
        assertSqlState("23514") { exec(insertJob, UUID.randomUUID(), submission, lab, session, "GRADE") }
        assertSqlState("23514") { exec(insertJob, UUID.randomUUID(), null, lab, session, "REPORT") }
        db { exec(insertJob, UUID.randomUUID(), null, lab, session, "PROVISION") }
        assertSqlState("23505") { exec(insertJob, UUID.randomUUID(), null, lab, session, "PROVISION") }
        db { exec(insertJob, UUID.randomUUID(), submission, null, session, "GRADE") }
        assertSqlState("23505") { exec(insertJob, UUID.randomUUID(), submission, null, session, "GRADE") }
    }

    @Test
    fun `job attempts stop at three`() {
        val (_, _, session) = db { fixture() }
        val submission = db { submission(session) }
        assertSqlState("23514") {
            exec("INSERT INTO jobs(id, submission_id, session_id, kind, state, attempt) VALUES (?, ?, ?, 'GRADE', 'RUNNING', 4)", UUID.randomUUID(), submission, session)
        }
    }

    @Test
    fun `only one active evaluation per submission`() {
        val (_, _, session) = db { fixture() }
        val submission = db { submission(session) }
        val insert = "INSERT INTO evaluations(id, submission_id, revision, policy_version, verdict, dimensions, gates) VALUES (?, ?, ?, 'p1', 'PASS', '{}', '[]')"
        db { exec(insert, UUID.randomUUID(), submission, 1) }
        assertSqlState("23505") { exec(insert, UUID.randomUUID(), submission, 2) }
    }

    @Test
    fun `evidence sequence is unique and rows are append-only`() {
        val (_, _, session) = db { fixture() }
        val insert = """INSERT INTO evidence(id, session_id, seq, event_type, source, trust_level, schema_version, occurred_at,
            payload_digest, previous_hash, hash) VALUES (?, ?, ?, 'SessionCreated', 'CONTROL', 'SERVER_VERIFIED', 1, now(), ?, ?, ?)"""
        val evidence = UUID.randomUUID()
        db { exec(insert, evidence, session, 1L, digest, "0".repeat(64), digest) }
        assertSqlState("23505") { exec(insert, UUID.randomUUID(), session, 1L, digest, digest, digest) }
        assertSqlState("P0001") { exec("UPDATE evidence SET event_type = 'Tampered' WHERE id = ?", evidence) }
        assertSqlState("P0001") { exec("DELETE FROM evidence WHERE id = ?", evidence) }
    }

    @Test
    fun `enum, digest and idempotency constraints reject invalid rows`() {
        val (owner, version, _) = db { fixture() }
        assertSqlState("23514") { session(owner, version, mode = "SPEEDRUN") }
        // Inserted rather than updated: V5 makes stored content immutable, which would reject an UPDATE first.
        assertSqlState("23514") {
            exec(
                """INSERT INTO scenario_versions(id, scenario_id, version_no, status, content_digest, oracle_digest, oracle_key,
                   rubric_version, engine_version, randomization_version, public_manifest)
                   SELECT ?, scenario_id, 2, 'DRAFT', 'not-a-digest', oracle_digest, 'o', 'r', 'e', 'x', '{}' FROM scenario_versions WHERE id = ?""",
                UUID.randomUUID(), version,
            )
        }
        assertSqlState("P0001") { exec("UPDATE scenario_versions SET content_digest = ? WHERE id = ?", "f".repeat(64), version) }
        val insertRecord = """INSERT INTO idempotency_records(owner_id, route, idempotency_key, request_digest, response_status, response_body, expires_at)
            VALUES (?, 'POST /v1/sessions', ?, ?, 201, '{}', now() + interval '24 hours')"""
        val key = UUID.randomUUID()
        db { exec(insertRecord, owner, key, digest) }
        assertSqlState("23505") { exec(insertRecord, owner, key, digest) }
    }

    @Test
    fun `duplicate consumer inbox entries are rejected`() {
        val event = UUID.randomUUID()
        db { exec("INSERT INTO consumer_inbox(consumer, event_id) VALUES ('report', ?)", event) }
        assertSqlState("23505") { exec("INSERT INTO consumer_inbox(consumer, event_id) VALUES ('report', ?)", event) }
        db { exec("INSERT INTO consumer_inbox(consumer, event_id) VALUES ('skills', ?)", event) }
    }

    @Test
    fun `one live refresh token per login and hashes only`() {
        val owner = db { user() }
        val login = UUID.randomUUID()
        db { exec("INSERT INTO auth_sessions(id, user_id, csrf_hash, created_at) VALUES (?, ?, ?, now())", login, owner, digest) }
        val insert = "INSERT INTO auth_tokens(token_hash, auth_session_id, kind, issued_at, expires_at) VALUES (?, ?, 'REFRESH', now(), now() + interval '7 days')"
        db { exec(insert, "1".repeat(64), login) }
        assertSqlState("23505") { exec(insert, "2".repeat(64), login) }
        assertSqlState("23514") { exec(insert, "raw-token-not-a-hash", login) }
        assertSqlState("23514") {
            exec("UPDATE auth_sessions SET revoked_at = now() WHERE id = ?", login)
        }
    }

    @Test
    fun `operator tokens live at most 12 hours and audit is append-only`() {
        val insertToken = "INSERT INTO operator_tokens(token_hash, operator_id, role, purpose, issued_at, expires_at) VALUES (?, ?, 'OPERATOR', 'review', now(), now() + ?::interval)"
        db { exec(insertToken, "3".repeat(64), UUID.randomUUID(), "12 hours") }
        assertSqlState("23514") { exec(insertToken, "4".repeat(64), UUID.randomUUID(), "13 hours") }
        val audit = UUID.randomUUID()
        db { exec("INSERT INTO audit_events(id, actor_type, actor_id, purpose, action, occurred_at) VALUES (?, 'OPERATOR', ?, 'review', 'GET /ops/v1/whoami', now())", audit, UUID.randomUUID()) }
        assertSqlState("P0001") { exec("UPDATE audit_events SET action = 'tampered' WHERE id = ?", audit) }
        assertSqlState("P0001") { exec("DELETE FROM audit_events WHERE id = ?", audit) }
    }

    @Test
    fun `edited applied migration fails validation`() {
        val directory = Files.createTempDirectory("migrations")
        val original = javaClass.getResource("/db/migration/V1__core_schema.sql")!!.readText()
        directory.resolve("V1__core_schema.sql").writeText(original + "\n-- edited after release\n")
        val result = Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("filesystem:$directory")
            .load()
            .validateWithResult()
        assertFalse(result.validationSuccessful)
    }
}
