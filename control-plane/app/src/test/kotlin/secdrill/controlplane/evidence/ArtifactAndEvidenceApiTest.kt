package secdrill.controlplane.evidence

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import secdrill.controlplane.access.ResourceNotFoundException
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.kernel.ApiException
import secdrill.kernel.ArtifactSensitivity
import secdrill.kernel.ErrorCode
import secdrill.kernel.EvidenceSource
import secdrill.kernel.TrustLevel
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Private Artifacts, the Evidence API and operational-log hygiene (14, 15, 22; prompt 05). */
@IntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class ArtifactAndEvidenceApiTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var artifacts: ArtifactService
    @Autowired private lateinit var ledger: LedgerAppender
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var properties: ArtifactProperties

    private val json = JsonMapper.builder().build()

    private fun stored(session: UUID, sensitivity: ArtifactSensitivity, bytes: ByteArray = "synthetic bytes ${UUID.randomUUID()}".toByteArray()) =
        transactions.execute { artifacts.store(session, sensitivity, "text/plain", bytes) }!!

    private fun principal(learner: Fixtures.Learner) = secdrill.controlplane.identity.LearnerPrincipal(learner.userId, learner.login.authSessionId, learner.login.accessExpiresAt, "0".repeat(64))

    @Test
    fun `owners read their artifacts with digest, size and retention recorded`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val bytes = "synthetic raw log line".toByteArray()
        val ref = stored(session, ArtifactSensitivity.RAW_LOG, bytes)
        assertEquals("sessions/$session/${ref.id}", ref.key)
        assertEquals(Duration.ofDays(30), Duration.between(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS), ref.expiresAt))
        assertContentEquals(bytes, artifacts.readForLearner(principal(learner), ref.id).bytes)
        assertEquals(ref.digest, fixtures.string("SELECT digest FROM artifacts WHERE id = ?", ref.id))
    }

    @Test
    fun `other owners, oracle material, expired and deleted artifacts are not found`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val mine = principal(learner)
        val log = stored(session, ArtifactSensitivity.RAW_LOG)
        assertFailsWith<ResourceNotFoundException> { artifacts.readForLearner(principal(fixtures.learner()), log.id) }
        val oracle = stored(session, ArtifactSensitivity.PRIVATE_ORACLE)
        assertFailsWith<ResourceNotFoundException> { artifacts.readForLearner(mine, oracle.id) }
        val deleted = stored(session, ArtifactSensitivity.LEARNER)
        jdbc.sql("UPDATE artifacts SET deleted_at = now() WHERE id = ?").param(deleted.id).update()
        assertFailsWith<ResourceNotFoundException> { artifacts.readForLearner(mine, deleted.id) }
        val expiring = stored(session, ArtifactSensitivity.RAW_LOG)
        clock.advance(Duration.ofDays(31))
        assertFailsWith<ResourceNotFoundException> { artifacts.readForLearner(mine, expiring.id) }
    }

    @Test
    fun `tampered bytes are refused`() {
        val learner = fixtures.learner()
        val ref = stored(fixtures.activeSession(learner.userId), ArtifactSensitivity.LEARNER)
        Files.writeString(properties.localDir.toAbsolutePath().normalize().resolve(ref.key), "tampered")
        val error = assertFailsWith<ApiException> { artifacts.readForLearner(principal(learner), ref.id) }
        assertEquals(ErrorCode.INTERNAL_ERROR, error.code)
    }

    @Test
    fun `local store rejects keys that could escape its root`() {
        val store = LocalArtifactStore(Files.createTempDirectory("artifacts"))
        listOf("../etc/passwd", "sessions/../../x", "/abs/path", "Sessions/${UUID.randomUUID()}")
            .forEach { assertFailsWith<IllegalArgumentException>(it) { store.put(it, byteArrayOf(1)) } }
    }

    @Test
    fun `evidence API pages by seq for the owner and hides other owners' sessions`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        repeat(3) { n ->
            transactions.execute { ledger.append(session, "TEST_RESULT", EvidenceSource.VERIFIER, TrustLevel.SERVER_VERIFIED, mapOf("n" to n)) }
        }
        val browser = TestBrowser("http://127.0.0.1:$port")
        val first = browser.send("GET", "/v1/sessions/$session/evidence?limit=2", cookies = learner.cookies)
        assertEquals(200, first.status, first.body)
        val page = json.readTree(first.body)
        assertEquals(listOf(1, 2), page["items"].values().map { it["seq"].asInt() })
        assertTrue(page["hasMore"].asBoolean())
        assertEquals(2, page["nextSeq"].asInt())
        assertEquals("SERVER_VERIFIED", page["items"][0]["trustLevel"].asString())
        assertEquals(0, page["items"][0]["summary"]["n"].asInt())
        val rest = json.readTree(browser.send("GET", "/v1/sessions/$session/evidence?afterSeq=2", cookies = learner.cookies).body)
        assertEquals(listOf(3), rest["items"].values().map { it["seq"].asInt() })
        assertFalse(rest["hasMore"].asBoolean())

        assertEquals(404, browser.send("GET", "/v1/sessions/$session/evidence", cookies = fixtures.learner().cookies).status)
        assertEquals(401, browser.send("GET", "/v1/sessions/$session/evidence", cookies = emptyMap()).status)
        assertEquals(422, browser.send("GET", "/v1/sessions/$session/evidence?limit=0", cookies = learner.cookies).status)
    }

    @Test
    fun `operational logs never contain raw flags or session tokens`(output: CapturedOutput) {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val flag = "SYNTHETIC-FLAG-${UUID.randomUUID()}"
        val browser = TestBrowser("http://127.0.0.1:$port")
        fun submit(body: String) = browser.send("POST", "/v1/sessions/$session/submissions", body = body, origin = TEST_ORIGIN,
            csrf = learner.login.csrfToken, cookies = learner.cookies, headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
        assertEquals(202, submit(Fixtures.flagBody(0, flag)).status)
        assertEquals(400, submit("""{"kind":"FLAG","content":{"flag":"$flag""").status)
        assertEquals(422, submit("""{"kind":"FLAG","expectedVersion":1,"content":{"challengeId":"x","flag":"$flag"}}""").status)
        assertEquals(401, browser.send("GET", "/v1/auth/session", cookies = mapOf("access_session" to "forged-${learner.login.accessToken}")).status)
        listOf(flag, learner.login.accessToken, learner.login.refreshToken, learner.login.csrfToken!!).forEach { secret ->
            assertFalse(secret in output.all, "a secret value reached the operational log")
        }
    }
}
