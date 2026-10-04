package secdrill.controlplane.access

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.identity.UserIdentityService
import secdrill.controlplane.identity.web.AuthCookies
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import secdrill.kernel.UserId
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Owner guard contract (FR-01, 15). Tables for Session, Submission, Artifact and Evidence exist, so the guard is
 * checked against real rows; Report is addressed by its Session id until T12. Public endpoints for these resources
 * do not exist yet and must call the guard when they are added (T04, T05, T09, T12).
 */
@IntegrationTest
@Import(OwnershipGuardTest.ProbeConfig::class)
class OwnershipGuardTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var guard: OwnershipGuard
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var identities: UserIdentityService
    @Autowired private lateinit var sessions: AuthSessionService

    private val digest = "b".repeat(64)
    private val json = JsonMapper.builder().build()

    @TestConfiguration(proxyBeanMethods = false)
    @Import(ProbeController::class)
    class ProbeConfig

    /** Stand-in for future resource endpoints: applies the guard exactly as they must. */
    @RestController
    class ProbeController(private val guard: OwnershipGuard) {
        @GetMapping("/v1/test-probe/sessions/{id}")
        fun session(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID): Map<String, String> {
            guard.requireOwned(principal, OwnedResource.SESSION, id)
            return mapOf("id" to id.toString())
        }
    }

    private data class Owned(val owner: UserId, val session: UUID, val submission: UUID, val artifact: UUID, val deletedArtifact: UUID, val evidence: UUID)

    private fun principal(user: UserId) = LearnerPrincipal(user, UUID.randomUUID(), Instant.MAX, digest)

    private fun seed(): Owned {
        val owner = identities.findOrCreate("https://idp.test", "owner-${UUID.randomUUID()}")
        val scenario = UUID.randomUUID()
        val version = UUID.randomUUID()
        val session = UUID.randomUUID()
        val submission = UUID.randomUUID()
        val artifact = UUID.randomUUID()
        val deleted = UUID.randomUUID()
        val evidence = UUID.randomUUID()
        jdbc.sql("INSERT INTO scenarios(id, slug, title) VALUES (?, ?, 'Synthetic')").params(scenario, "s-$scenario").update()
        jdbc.sql(
            """INSERT INTO scenario_versions(id, scenario_id, version_no, status, content_digest, oracle_digest, oracle_key,
               rubric_version, engine_version, randomization_version, public_manifest) VALUES (?, ?, 1, 'PUBLISHED', ?, ?, 'o', 'r', 'e', 'x', '{}')""",
        ).params(version, scenario, digest, digest).update()
        jdbc.sql(
            """INSERT INTO sessions(id, owner_id, scenario_version_id, mode, status, phase, seed, rubric_version, engine_version, randomization_version)
               VALUES (?, ?, ?, 'CTF', 'ACTIVE', 'ANALYZE', '\x01', 'r', 'e', 'x')""",
        ).params(session, owner.value, version).update()
        val insertArtifact = { deletedAt: String -> """INSERT INTO artifacts(id, session_id, object_key, digest, byte_size, media_type, sensitivity, deleted_at)
            VALUES (?, ?, ?, ?, 1, 'text/plain', 'LEARNER', $deletedAt)""" }
        jdbc.sql(insertArtifact("NULL")).params(artifact, session, "k/$artifact", digest).update()
        jdbc.sql(insertArtifact("now()")).params(deleted, session, "k/$deleted", digest).update()
        jdbc.sql("INSERT INTO submissions(id, session_id, kind, client_request_id, request_digest, status) VALUES (?, ?, 'FLAG', ?, ?, 'ACCEPTED')")
            .params(submission, session, UUID.randomUUID(), digest).update()
        jdbc.sql(
            """INSERT INTO evidence(id, session_id, seq, event_type, source, trust_level, schema_version, occurred_at, payload_digest, previous_hash, hash)
               VALUES (?, ?, 1, 'SessionCreated', 'CONTROL', 'SERVER_VERIFIED', 1, now(), ?, ?, ?)""",
        ).params(evidence, session, digest, digest, digest).update()
        return Owned(owner, session, submission, artifact, deleted, evidence)
    }

    @Test
    fun `owner passes for every resource type`() {
        val owned = seed()
        val me = principal(owned.owner)
        guard.requireOwned(me, OwnedResource.SESSION, owned.session)
        guard.requireOwned(me, OwnedResource.REPORT, owned.session)
        guard.requireOwned(me, OwnedResource.SUBMISSION, owned.submission)
        guard.requireOwned(me, OwnedResource.ARTIFACT, owned.artifact)
        guard.requireOwned(me, OwnedResource.EVIDENCE, owned.evidence)
    }

    @Test
    fun `other owners, missing ids and deleted artifacts are all not found`() {
        val owned = seed()
        val stranger = principal(identities.findOrCreate("https://idp.test", "stranger-${UUID.randomUUID()}"))
        val cases = mapOf(
            OwnedResource.SESSION to owned.session,
            OwnedResource.REPORT to owned.session,
            OwnedResource.SUBMISSION to owned.submission,
            OwnedResource.ARTIFACT to owned.artifact,
            OwnedResource.EVIDENCE to owned.evidence,
        )
        cases.forEach { (type, id) ->
            assertFailsWith<ResourceNotFoundException>("$type of another owner") { guard.requireOwned(stranger, type, id) }
            assertFailsWith<ResourceNotFoundException>("missing $type") { guard.requireOwned(principal(owned.owner), type, UUID.randomUUID()) }
        }
        assertFailsWith<ResourceNotFoundException> { guard.requireOwned(principal(owned.owner), OwnedResource.ARTIFACT, owned.deletedArtifact) }
    }

    @Test
    fun `over HTTP another owner's session is indistinguishable from a missing one`() {
        val owned = seed()
        val browser = TestBrowser("http://127.0.0.1:$port")
        val ownerLogin = sessions.start(owned.owner)
        val strangerLogin = sessions.start(identities.findOrCreate("https://idp.test", "stranger-${UUID.randomUUID()}"))

        val own = browser.send("GET", "/v1/test-probe/sessions/${owned.session}", cookies = mapOf(AuthCookies.ACCESS to ownerLogin.accessToken))
        assertEquals(200, own.status, own.body)

        val strangerCookie = mapOf(AuthCookies.ACCESS to strangerLogin.accessToken)
        val foreign = browser.send("GET", "/v1/test-probe/sessions/${owned.session}", cookies = strangerCookie)
        val missing = browser.send("GET", "/v1/test-probe/sessions/${UUID.randomUUID()}", cookies = strangerCookie)
        assertEquals(404, foreign.status)
        assertEquals(404, missing.status)
        val strip = { body: String -> json.readTree(body).let { (it as tools.jackson.databind.node.ObjectNode).remove("requestId"); it } }
        assertEquals(strip(missing.body), strip(foreign.body))

        assertEquals(401, browser.send("GET", "/v1/test-probe/sessions/${owned.session}", cookies = emptyMap()).status)
    }
}
