package secdrill.controlplane.privacy

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import secdrill.controlplane.evidence.ArtifactService
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.identity.OperatorPrincipal
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.kernel.ArtifactSensitivity
import secdrill.kernel.EvidenceSource
import secdrill.kernel.OperatorRole
import secdrill.kernel.TrustLevel
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Privacy data-subject execution (T14 ops, 14, 25, ADR 0013): export, erasure, retention sweep and tombstone
 * reapply over the real schema. The dedicated role model is proven separately by [PrivacyPrivilegeTest].
 */
@IntegrationTest
@TestPropertySource(properties = ["secdrill.privacy.orphan-grace=0s"])
class PrivacyExecutionTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var artifacts: ArtifactService
    @Autowired private lateinit var store: ArtifactStore
    @Autowired private lateinit var ledger: LedgerAppender
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired private lateinit var exports: ExportService
    @Autowired private lateinit var deletions: DeletionService
    @Autowired private lateinit var eraser: PrivacyEraser
    @Autowired private lateinit var retention: RetentionSweeper
    @Autowired private lateinit var clock: Clock

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
    }

    private fun admin(id: UUID = UUID.randomUUID()) = OperatorPrincipal(id, OperatorRole.SECURITY_ADMIN, "privacy review")

    private fun requestDeletion(learner: Fixtures.Learner, scope: String, sessionId: UUID? = null): UUID {
        val body = buildString {
            append("""{"scope":"$scope","confirmationToken":"${learner.login.csrfToken}"""")
            sessionId?.let { append(""","sessionId":"$it"""") }
            append("}")
        }
        val response = browser.send(
            "POST", "/v1/deletion-requests", body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken,
            cookies = learner.cookies, headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertEquals(202, response.status, response.body)
        return UUID.fromString(json.readTree(response.body)["id"].asString())
    }

    @Test
    fun `account deletion erases bytes, identity and projections, keeps the ledger, issues a receipt`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val artifact = artifacts.store(session, ArtifactSensitivity.LEARNER, "application/json", """{"n":1}""".toByteArray())
        transactions.execute { ledger.append(session, "TEST_RESULT", EvidenceSource.CONTROL, TrustLevel.SERVER_VERIFIED, mapOf("n" to 1)) }
        jdbc.sql("INSERT INTO reports(id, session_id, revision, policy_version, evaluation_refs, payload) VALUES (?, ?, 1, 'p', '[]'::jsonb, '{}'::jsonb)")
            .params(UUID.randomUUID(), session).update()
        assertNotNull(store.get(artifact.key))
        val identityBefore = fixtures.count("SELECT count(*) FROM user_identities WHERE user_id = ?", learner.userId.value)
        assertEquals(1, identityBefore)

        val request = requestDeletion(learner, "ACCOUNT")
        deletions.decide(admin(), request, approve = true)
        assertEquals(request, eraser.runOnce())

        // Bytes, artifact row, projection and identity linkage are gone; the ledger row and its hash stay.
        assertNull(store.get(artifact.key), "artifact bytes purged")
        assertEquals(0, fixtures.count("SELECT count(*) FROM artifacts WHERE id = ?", artifact.id))
        assertEquals(0, fixtures.count("SELECT count(*) FROM reports WHERE session_id = ?", session))
        assertEquals(0, fixtures.count("SELECT count(*) FROM user_identities WHERE user_id = ?", learner.userId.value))
        assertEquals(1, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ?", session), "the ledger row is kept")
        // Access revoked and the request completed with a receipt and tombstones.
        assertEquals(0, fixtures.count("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL", learner.userId.value))
        val completed = jdbc.sql("SELECT status, receipt_digest FROM deletion_requests WHERE id = ?").param(request)
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        assertEquals("COMPLETED", completed.first)
        assertTrue(completed.second!!.matches(Regex("^[a-f0-9]{64}$")))
        assertEquals(1, fixtures.count("SELECT count(*) FROM deletion_tombstones WHERE subject_type = 'USER' AND subject_id = ?", learner.userId.value))
        assertEquals(1, fixtures.count("SELECT count(*) FROM deletion_tombstones WHERE subject_type = 'ARTIFACT' AND subject_id = ?", artifact.id))
        assertEquals(1, fixtures.count("SELECT count(*) FROM audit_events WHERE purpose = 'privacy erasure' AND action LIKE ?", "%$request%"))
    }

    @Test
    fun `session deletion is scoped to one session and leaves the other`() {
        val learner = fixtures.learner()
        val kept = fixtures.activeSession(learner.userId)
        val erased = fixtures.activeSession(learner.userId)
        val keptArtifact = artifacts.store(kept, ArtifactSensitivity.LEARNER, "application/json", "{}".toByteArray())
        val erasedArtifact = artifacts.store(erased, ArtifactSensitivity.LEARNER, "application/json", "{}".toByteArray())

        val request = requestDeletion(learner, "SESSION", erased)
        deletions.decide(admin(), request, approve = true)
        eraser.runOnce()

        assertNull(store.get(erasedArtifact.key))
        assertEquals(0, fixtures.count("SELECT count(*) FROM artifacts WHERE id = ?", erasedArtifact.id))
        assertNotNull(store.get(keptArtifact.key))
        assertEquals(1, fixtures.count("SELECT count(*) FROM artifacts WHERE id = ?", keptArtifact.id))
        // A SESSION erasure keeps the account and its identity.
        assertEquals(1, fixtures.count("SELECT count(*) FROM user_identities WHERE user_id = ?", learner.userId.value))
    }

    @Test
    fun `an unapproved request cannot be executed and the requester cannot approve`() {
        val learner = fixtures.learner()
        val request = requestDeletion(learner, "ACCOUNT")
        // The executor function refuses a request that is not APPROVED.
        assertFailsWith<Exception> {
            jdbc.sql("SELECT erase_deletion_request(?, ?, ?)")
                .params(request, "a".repeat(64), OffsetDateTime.now(ZoneOffset.UTC)).query(String::class.java).list()
        }
        // Only a SECURITY_ADMIN may decide.
        assertFailsWith<secdrill.kernel.ApiException> {
            deletions.decide(OperatorPrincipal(UUID.randomUUID(), OperatorRole.OPERATOR, "ops"), request, approve = true)
        }
        assertEquals("REQUESTED", fixtures.string("SELECT status FROM deletion_requests WHERE id = ?", request))
    }

    @Test
    fun `export assembles the owner's data, is owner-only to download, and holds no oracle`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val created = browser.send("POST", "/v1/exports", body = "{}", origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies)
        assertEquals(202, created.status, created.body)
        val id = UUID.fromString(json.readTree(created.body)["id"].asString())
        assertNotNull(exports.runOnce())

        val poll = browser.send("GET", "/v1/async-jobs/$id", cookies = learner.cookies)
        assertEquals("COMPLETED", json.readTree(poll.body)["status"].asString())
        val downloadPath = json.readTree(poll.body)["downloadPath"].asString()
        val download = browser.send("GET", downloadPath, cookies = learner.cookies)
        assertEquals(200, download.status, download.body)
        val export = json.readTree(download.body)
        assertEquals("secdrill-export/1", export["format"].asString())
        assertTrue(export["sessions"].values().any { it["id"].asString() == session.toString() })
        assertTrue(!download.body.contains("oracle") && !download.body.contains("hiddenTests"), "export carries no grader-only data")

        // Another learner cannot download or even see the job.
        val other = fixtures.learner()
        assertEquals(404, browser.send("GET", "/v1/async-jobs/$id", cookies = other.cookies).status)
        assertEquals(404, browser.send("GET", downloadPath, cookies = other.cookies).status)
    }

    @Test
    fun `retention sweep purges expired artifact bytes and soft-deletes the row`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val artifact = artifacts.store(session, ArtifactSensitivity.RAW_LOG, "text/plain", "synthetic".toByteArray())
        jdbc.sql("UPDATE artifacts SET expires_at = ? WHERE id = ?")
            .params(clock.instant().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC), artifact.id).update()

        val result = retention.sweepOnce()
        assertTrue(result.expired >= 1)
        assertNull(store.get(artifact.key))
        assertNotNull(fixtures.string("SELECT deleted_at::text FROM artifacts WHERE id = ?", artifact.id))
    }

    @Test
    fun `tombstone reapply re-erases a subject that a restore brought back`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val artifact = artifacts.store(session, ArtifactSensitivity.LEARNER, "application/json", "{}".toByteArray())
        val request = requestDeletion(learner, "ACCOUNT")
        deletions.decide(admin(), request, approve = true)
        eraser.runOnce()
        assertNull(store.get(artifact.key))

        // Simulate a restore: the artifact row, its bytes and an identity row come back.
        jdbc.sql("INSERT INTO artifacts(id, session_id, object_key, digest, byte_size, media_type, sensitivity) VALUES (?, ?, ?, ?, 2, 'application/json', 'LEARNER')")
            .params(artifact.id, session, artifact.key, artifact.digest).update()
        store.put(artifact.key, "{}".toByteArray())
        jdbc.sql("INSERT INTO user_identities(issuer, subject, user_id) VALUES ('https://idp.test', ?, ?)")
            .params("restored-${UUID.randomUUID()}", learner.userId.value).update()

        assertTrue(eraser.reapplyTombstones() >= 1)
        assertNull(store.get(artifact.key), "restored bytes re-erased")
        assertEquals(0, fixtures.count("SELECT count(*) FROM artifacts WHERE id = ?", artifact.id))
        assertEquals(0, fixtures.count("SELECT count(*) FROM user_identities WHERE user_id = ?", learner.userId.value))
    }

    @Test
    fun `orphan sweep removes an object no row references`() {
        val orphan = "exports/${UUID.randomUUID()}"
        store.put(orphan, "orphaned".toByteArray())
        // The sweep compares object age to the clock; move it past the orphan's real write time with a zero grace.
        (clock as MutableClock).advance(Duration.ofDays(30))

        assertTrue(retention.sweepOnce().orphans >= 1)
        assertNull(store.get(orphan))
    }
}
