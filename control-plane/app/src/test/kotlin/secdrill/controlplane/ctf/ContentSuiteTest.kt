package secdrill.controlplane.ctf

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.catalog.ContentPublishingTest
import secdrill.controlplane.catalog.ContentTestSupport
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.identity.WorkloadCredentialService
import secdrill.controlplane.lab.FakeRuntime
import secdrill.controlplane.lab.LocalTrustedIsolationTest
import secdrill.controlplane.platform.OutboxPublisher
import secdrill.controlplane.submission.JobLeaseService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.execution.agent.GradeAgent
import secdrill.execution.agent.HttpGradeControl
import secdrill.execution.agent.LocalTrustedDockerAdapter
import secdrill.execution.agent.ObjectiveObserver
import secdrill.execution.agent.PatchGrader
import secdrill.kernel.OperatorRole
import secdrill.kernel.WorkloadKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * MVP content gate (T13, 08, 03): the reference patch of each incident family is VERIFIED and every key mutant is
 * NOT_VERIFIED through the real grading path. Runs on the local-trusted Docker profile, so every result is a demo
 * result (D-10): this gate proves the content and grading agree, not that isolation is strong. The tenant-leak
 * family is graded by [PatchGradingTest]; this covers the webhook-replay and over-broad-token families.
 */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["test.context=content-suite"])
class ContentSuiteTest {
    companion object {
        const val RUNNER = "runner-content-suite"

        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun properties(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
        }

        /** Each family: (LabContent, expected failing gate per key mutant). The reference is always VERIFIED. */
        private val WEBHOOK = LabContent("webhook-receiver", "Synthetic webhook replay", listOf("app/verify.py", "app/dedup.py"),
            setOf("fresh-delivery-processed", "legitimate-retry-accepted"))
        private val API = LabContent("api-gateway", "Synthetic over-broad API token", listOf("app/tokens.py", "app/scopes.py"),
            setOf("in-scope-read", "in-scope-automation-read"))

        private val MUTANTS = mapOf(
            WEBHOOK to mapOf(
                "no-change" to "security", "dedup-only" to "security", "freshness-only" to "security",
                "reject-everything" to "regression", "dedup-by-amount" to "regression", "tamper-framework" to "security", "compile-error" to "compile",
            ),
            API to mapOf(
                "no-change" to "security", "reports-implies-all" to "security", "trust-client-scope" to "security",
                "fix-scope-not-revoke" to "security", "reject-everything" to "regression", "compile-error" to "compile",
            ),
        )
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var workloads: WorkloadCredentialService
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var jobs: JobLeaseService
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private val docker = LocalTrustedDockerAdapter(RUNNER, "test-ownership-key-not-a-secret".toByteArray(), LocalTrustedIsolationTest.IMAGE, relayImage = CtfFlowTest.PYTHON)

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").update()
    }

    private fun publish(content: LabContent): UUID {
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author ${content.root.fileName}", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review ${content.root.fileName}", Duration.ofHours(1))
        val registered = ContentTestSupport.register(browser, author, content.bundle())
        assertEquals(201, registered.status, registered.body)
        val id = UUID.fromString(json.readTree(registered.body)["scenarioVersionId"].asString())
        assertEquals("PASS", json.readTree(ContentTestSupport.validate(browser, author, id).body)["status"].asString())
        assertEquals(204, ContentTestSupport.approve(browser, reviewer, id).status)
        return id
    }

    private fun send(learner: Fixtures.Learner, path: String, body: String) =
        browser.send("POST", path, body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies, headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()))

    private fun version(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()

    private fun grade(versionId: UUID, files: Map<String, String>): JsonNode {
        val learner = fixtures.learner()
        val created = send(learner, "/v1/sessions", """{"scenarioVersionId":"$versionId","mode":"PURPLE"}""")
        assertEquals(201, created.status, created.body)
        val session = UUID.fromString(json.readTree(created.body)["id"].asString())
        jdbc.sql("UPDATE sessions SET status = 'ACTIVE' WHERE id = ?").param(session).update()
        val body = json.writeValueAsString(mapOf("kind" to "PATCH", "expectedVersion" to version(session), "content" to mapOf("files" to files, "explanation" to "Synthetic suite patch.")))
        val submitted = send(learner, "/v1/sessions/$session/submissions", body)
        assertEquals(202, submitted.status, submitted.body)
        val submission = UUID.fromString(json.readTree(submitted.body)["id"].asString())
        val job = UUID.fromString(fixtures.string("SELECT id::text FROM jobs WHERE submission_id = ?", submission))
        publisher.publishOnce()
        fixtures.await { fixtures.string("SELECT state FROM jobs WHERE id = ?", job) == "DISPATCHED" }
        val control = HttpGradeControl("http://127.0.0.1:$port", workloads.issue(RUNNER, WorkloadKind.AGENT, Duration.ofHours(1)))
        val agent = GradeAgent(control, ObjectiveObserver(FakeRuntime()), PatchGrader { lease, task -> docker.gradePatch(lease.jobId, lease.attempt, task) })
        assertNotNull(agent.runOnce(), "the grading runner takes the PATCH job")
        return browser.send("GET", "/v1/submissions/$submission", cookies = learner.cookies).let { json.readTree(it.body)["evaluation"] }
    }

    private fun gates(evaluation: JsonNode) = evaluation["gates"].values().associate { it["key"].asString() to it["result"].asString() }

    private fun verifyFamily(content: LabContent) {
        val versionId = publish(content)
        val reference = grade(versionId, content.patch("reference"))
        assertEquals("PASS", reference["verdict"].asString(), "reference gates=${gates(reference)}: $reference")
        assertEquals("VERIFIED", reference["patchGate"].asString())
        assertEquals(setOf("compile" to "PASS", "security" to "PASS", "regression" to "PASS"), gates(reference).entries.map { it.key to it.value }.toSet())
        assertTrue(reference["demo"].asBoolean(), "local-trusted grading is a demo result")

        MUTANTS.getValue(content).forEach { (mutant, failingGate) ->
            val evaluation = grade(versionId, content.patch(mutant))
            assertEquals("FAIL", evaluation["verdict"].asString(), "$mutant must not pass: $evaluation")
            assertEquals("NOT_VERIFIED", evaluation["patchGate"].asString(), mutant)
            assertEquals("FAIL", gates(evaluation)[failingGate], "$mutant must fail the $failingGate gate: ${gates(evaluation)}")
        }
    }

    @Test
    fun `webhook replay - reference is VERIFIED and every key mutant is detected`() = verifyFamily(WEBHOOK)

    @Test
    fun `over-broad API token - reference is VERIFIED and every key mutant is detected`() = verifyFamily(API)
}
