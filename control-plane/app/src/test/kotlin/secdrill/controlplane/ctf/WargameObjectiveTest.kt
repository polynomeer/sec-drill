package secdrill.controlplane.ctf

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import secdrill.content.SyntheticBundles
import secdrill.controlplane.catalog.ContentPublishingTest
import secdrill.controlplane.catalog.ContentTestSupport
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.identity.WorkloadCredentialService
import secdrill.controlplane.lab.FakeRuntime
import secdrill.controlplane.lab.LabCalls
import secdrill.controlplane.platform.OutboxPublisher
import secdrill.controlplane.submission.CtfGradingService
import secdrill.controlplane.submission.JobLeaseService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.execution.agent.HttpLabControl
import secdrill.execution.agent.RunnerAgent
import secdrill.execution.fake.FakeGradingWorker
import secdrill.execution.protocol.ObjectiveObservation
import secdrill.kernel.OperatorRole
import secdrill.kernel.WorkloadKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Wargame OBJECTIVE verification (FR-04, 09, ADR 0014): the independent observer confirms objective access, the
 * explanation is recorded separately, and no flag is involved. Observed = PASS, not observed = FAIL. The Lab runs
 * on the in-memory runtime, so grading is driven at the service boundary with a chosen observation (demo result).
 */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["test.context=wargame-objective"])
class WargameObjectiveTest {
    companion object {
        const val RUNNER = "runner-objective"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
        }
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var workloads: WorkloadCredentialService
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var jobs: JobLeaseService
    @Autowired private lateinit var ctf: CtfGradingService
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private lateinit var versionId: UUID
    private lateinit var objectiveChallenge: UUID

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").update()
        publish()
    }

    private fun publish() {
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author wargame", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review wargame", Duration.ofHours(1))
        objectiveChallenge = UUID.randomUUID()
        val bundle = SyntheticBundles.withManifest(SyntheticBundles.valid()) {
            val flagId = it["challenges"][0]["id"].asString()
            it.set("modes", json.valueToTree(listOf("WARGAME", "PURPLE")))
            it.set("completionRequirements", json.valueToTree(mapOf("WARGAME" to listOf("objective_confirmed"), "PURPLE" to listOf("objective_confirmed"))))
            it.set("challenges", json.valueToTree(listOf(
                mapOf("id" to flagId, "key" to "cross-tenant-order", "kind" to "FLAG", "objective" to "Prove the tenant boundary violation.", "basePoints" to 100),
                mapOf("id" to objectiveChallenge.toString(), "key" to "impactful-access-path", "kind" to "OBJECTIVE", "objective" to "Find and prove an impactful access path.", "basePoints" to 100),
            )))
        }
        val registered = ContentTestSupport.register(browser, author, bundle)
        assertEquals(201, registered.status, registered.body)
        versionId = UUID.fromString(json.readTree(registered.body)["scenarioVersionId"].asString())
        assertEquals(201, ContentTestSupport.validate(browser, author, versionId).status)
        assertEquals(204, ContentTestSupport.approve(browser, reviewer, versionId).status)
    }

    private fun send(learner: Fixtures.Learner, method: String, path: String, body: String? = null, key: String? = UUID.randomUUID().toString()): TestResponse =
        browser.send(method, path, body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies,
            headers = key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())

    private fun version(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()

    private fun wargameSessionWithLab(learner: Fixtures.Learner): UUID {
        val created = send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"$versionId","mode":"WARGAME"}""")
        assertEquals(201, created.status, created.body)
        val session = UUID.fromString(json.readTree(created.body)["id"].asString())
        val credential = workloads.issue(RUNNER, WorkloadKind.AGENT, Duration.ofHours(1))
        val agent = RunnerAgent(HttpLabControl("http://127.0.0.1:$port", credential), FakeRuntime())
        val requested = LabCalls.requestLab(browser, learner, session, version(session))
        assertEquals(202, requested.status, requested.body)
        val lab = UUID.fromString(json.readTree(requested.body)["id"].asString())
        generateSequence { agent.runOnce() }.first { it.lab.labId == lab }
        assertEquals("READY", fixtures.string("SELECT state FROM labs WHERE id = ?", lab))
        return session
    }

    private fun submitObjective(learner: Fixtures.Learner, session: UUID): UUID {
        val body = """{"kind":"OBJECTIVE","expectedVersion":${version(session)},"content":{"challengeId":"$objectiveChallenge","evidenceSeqs":[1],"resourceId":"order-1003","explanation":"The other tenant's order is readable with my token."}}"""
        val accepted = send(learner, "POST", "/v1/sessions/$session/submissions", body)
        assertEquals(202, accepted.status, accepted.body)
        return UUID.fromString(json.readTree(accepted.body)["id"].asString())
    }

    private fun grade(submission: UUID, observed: Boolean?) {
        val job = UUID.fromString(fixtures.string("SELECT id::text FROM jobs WHERE submission_id = ? AND kind = 'GRADE'", submission))
        publisher.publishOnce()
        fixtures.await { fixtures.string("SELECT state FROM jobs WHERE id = ?", job) == "DISPATCHED" }
        assertNull(FakeGradingWorker(jobs).runOnce(), "a generic worker never grades objectives")
        val assignment = assertNotNull(ctf.claim(RUNNER), "the Lab-hosting runner claims the objective job")
        assertEquals(submission, assignment.lease.submissionId)
        assertNotNull(assignment.objective, "an objective task is built for observation")
        ctf.start(RUNNER, assignment.lease)
        ctf.observed(RUNNER, assignment.lease, ObjectiveObservation(observed, if (observed == true) 2 else 0, "obs-digest"))
    }

    private fun evaluation(learner: Fixtures.Learner, submission: UUID): JsonNode =
        json.readTree(send(learner, "GET", "/v1/submissions/$submission", key = null).body)["evaluation"]

    @Test
    fun `an observed objective passes and records the hypothesis separately`() {
        val learner = fixtures.learner()
        val session = wargameSessionWithLab(learner)
        val submission = submitObjective(learner, session)
        // The explanation is recorded as the learner's claim regardless of the verdict.
        assertEquals(1, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'HYPOTHESIS_REPORTED' AND trust_level = 'USER_REPORTED'", session))

        grade(submission, observed = true)

        val evaluation = evaluation(learner, submission)
        assertEquals("PASS", evaluation["verdict"].asString(), evaluation.toString())
        assertEquals(mapOf("objective" to "PASS"), evaluation["gates"].values().associate { it["key"].asString() to it["result"].asString() })
        assertEquals(1, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'OBJECTIVE_CONFIRMED' AND trust_level = 'OBSERVED' AND source = 'COLLECTOR'", session))
    }

    @Test
    fun `an unobserved objective fails without an OBJECTIVE_CONFIRMED record`() {
        val learner = fixtures.learner()
        val session = wargameSessionWithLab(learner)
        val submission = submitObjective(learner, session)

        grade(submission, observed = false)

        val evaluation = evaluation(learner, submission)
        assertEquals("FAIL", evaluation["verdict"].asString(), evaluation.toString())
        assertEquals(mapOf("objective" to "FAIL"), evaluation["gates"].values().associate { it["key"].asString() to it["result"].asString() })
        assertEquals(0, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'OBJECTIVE_CONFIRMED'", session))
    }

    @Test
    fun `an objective challenge is required and a live Lab must exist`() {
        val learner = fixtures.learner()
        // No Lab yet: the objective cannot be verified, so the submission is refused rather than left ungraded.
        val created = send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"$versionId","mode":"WARGAME"}""")
        val session = UUID.fromString(json.readTree(created.body)["id"].asString())
        val body = """{"kind":"OBJECTIVE","expectedVersion":${version(session)},"content":{"challengeId":"$objectiveChallenge","evidenceSeqs":[1],"resourceId":"r","explanation":"x"}}"""
        assertEquals(409, send(learner, "POST", "/v1/sessions/$session/submissions", body).status, "no live Lab to verify the objective")
    }
}
