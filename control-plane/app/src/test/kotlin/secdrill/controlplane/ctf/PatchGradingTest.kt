package secdrill.controlplane.ctf

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.execution.agent.GradeAgent
import secdrill.execution.agent.HttpGradeControl
import secdrill.execution.agent.LocalTrustedDockerAdapter
import secdrill.execution.agent.ObjectiveObserver
import secdrill.execution.agent.PatchGrader
import secdrill.execution.fake.FakeGradingWorker
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.PatchObservation
import secdrill.execution.protocol.PatchRunOutcome
import secdrill.kernel.OperatorRole
import secdrill.kernel.WorkloadKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T08 Python patch grading (prompt 09) with the fixtures the prompt names. Grading runs on the local-trusted Docker
 * profile in a fresh environment per job (not the grading-strong microVM): every result is demo and says so.
 */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["test.context=patch-grading"])
class PatchGradingTest {
    companion object {
        const val RUNNER = "runner-grading"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
        }
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var workloads: WorkloadCredentialService
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var jobs: JobLeaseService
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private val docker = LocalTrustedDockerAdapter(RUNNER, "test-ownership-key-not-a-secret".toByteArray(), LocalTrustedIsolationTest.IMAGE,
        relayImage = CtfFlowTest.PYTHON)

    private val version: UUID by lazy { publish() }

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").update()
    }

    private fun publish(): UUID {
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author synthetic patch scenario", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review synthetic patch scenario", Duration.ofHours(1))
        val registered = ContentTestSupport.register(browser, author, TenantOrders.bundle())
        assertEquals(201, registered.status, registered.body)
        val id = UUID.fromString(json.readTree(registered.body)["scenarioVersionId"].asString())
        val report = ContentTestSupport.validate(browser, author, id)
        assertEquals("PASS", json.readTree(report.body)["status"].asString(), report.body)
        assertEquals(204, ContentTestSupport.approve(browser, reviewer, id).status)
        return id
    }

    private fun send(learner: Fixtures.Learner, method: String, path: String, body: String? = null, key: String? = UUID.randomUUID().toString()): TestResponse =
        browser.send(method, path, body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies,
            headers = key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())

    private fun session(learner: Fixtures.Learner, mode: String = "PURPLE"): UUID {
        val created = send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"$version","mode":"$mode"}""")
        assertEquals(201, created.status, created.body)
        val id = UUID.fromString(json.readTree(created.body)["id"].asString())
        // Patch practice needs no Lab; the Session is made ACTIVE as a ready Lab would (13).
        jdbc.sql("UPDATE sessions SET status = 'ACTIVE' WHERE id = ?").param(id).update()
        return id
    }

    private fun version(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()

    private fun submitPatch(learner: Fixtures.Learner, session: UUID, files: Map<String, String>, explanation: String = "Synthetic fix explanation."): TestResponse {
        val body = json.writeValueAsString(mapOf("kind" to "PATCH", "expectedVersion" to version(session), "content" to mapOf("files" to files, "explanation" to explanation)))
        return send(learner, "POST", "/v1/sessions/$session/submissions", body)
    }

    private fun dispatched(response: TestResponse): UUID {
        assertEquals(202, response.status, response.body)
        val submission = UUID.fromString(json.readTree(response.body)["id"].asString())
        val job = UUID.fromString(fixtures.string("SELECT id::text FROM jobs WHERE submission_id = ?", submission))
        publisher.publishOnce()
        fixtures.await { fixtures.string("SELECT state FROM jobs WHERE id = ?", job) == "DISPATCHED" }
        return submission
    }

    private fun agent(grader: PatchGrader? = PatchGrader { lease, task -> docker.gradePatch(lease.jobId, lease.attempt, task) }): Pair<GradeAgent, HttpGradeControl> {
        val control = HttpGradeControl("http://127.0.0.1:$port", workloads.issue(RUNNER, WorkloadKind.AGENT, Duration.ofHours(1)))
        return GradeAgent(control, ObjectiveObserver(FakeRuntime()), grader) to control
    }

    private fun evaluation(learner: Fixtures.Learner, submission: UUID): JsonNode =
        json.readTree(send(learner, "GET", "/v1/submissions/$submission", key = null).body)["evaluation"]

    private fun gates(evaluation: JsonNode) = evaluation["gates"].values().associate { it["key"].asString() to it["result"].asString() }

    private fun grade(name: String): Pair<Fixtures.Learner, JsonNode> {
        val learner = fixtures.learner()
        val submission = dispatched(submitPatch(learner, session(learner), TenantOrders.patch(name)))
        assertNotNull(agent().first.runOnce(), "the grading runner takes the PATCH job")
        return learner to evaluation(learner, submission)
    }

    private fun hiddenValues(): List<String> {
        val plan = json.readTree(TenantOrders.privateDir.resolve("hidden-tests.json").toFile())
        return plan["tests"].values().flatMap { listOf(it["id"].asString(), it["request"]["path"].asString()) } + "X-Tenant" + "lab-alice"
    }

    private fun assertNoHiddenLeak(vararg bodies: String) =
        bodies.forEach { body -> hiddenValues().forEach { assertFalse(it in body, "hidden test data '$it' reached a learner-facing response") } }

    @Test
    fun `the reference patch is VERIFIED only with every mandatory gate, and the grading environment has no way out`(output: CapturedOutput) {
        val (learner, evaluation) = grade("reference")
        assertEquals("PASS", evaluation["verdict"].asString(), evaluation.toString())
        assertEquals("VERIFIED", evaluation["patchGate"].asString())
        assertEquals(mapOf("compile" to "PASS", "security" to "PASS", "regression" to "PASS"), gates(evaluation))
        assertTrue(evaluation["demo"].asBoolean(), "local-trusted grading is a demo result")
        assertEquals("python-patch/1", evaluation["policyVersion"].asString())
        assertNoHiddenLeak(evaluation.toString())
        hiddenValues().forEach { assertFalse(it in output.all, "hidden test data '$it' reached the logs") }

        // Same fix plus a probe that kills the app if it can reach the internet or a Docker socket: still VERIFIED.
        assertEquals("VERIFIED", grade("egress-probe").second["patchGate"].asString(), "grading has no egress and no Docker socket")
        assertEquals(0, fixtures.count("SELECT count(*) FROM labs WHERE owner_id = ?", learner.userId.value), "grading never uses a learner Lab")
        val leftovers = ProcessBuilder("docker", "ps", "-aq", "--filter", "label=secdrill.role=grading", "--filter", "label=secdrill.runner=$RUNNER")
            .redirectErrorStream(true).start().inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        assertEquals("", leftovers, "every grading container is removed")
        assertEquals(1, fixtures.count("SELECT count(*) FROM evidence WHERE event_type = 'TEST_RESULT' AND source = 'SUPERVISOR' AND safe_payload->>'submissionId' IN (SELECT s.id::text FROM submissions s JOIN sessions se ON se.id = s.session_id WHERE se.owner_id = ?)", learner.userId.value))
    }

    @Test
    fun `mutants and bad patches are never VERIFIED and fail the right gate`() {
        val expected = mapOf(
            "no-change" to mapOf("compile" to "PASS", "security" to "FAIL", "regression" to "PASS"),
            "deny-everything" to mapOf("compile" to "PASS", "security" to "PASS", "regression" to "FAIL"),
            "fix-only-direct-route" to mapOf("compile" to "PASS", "security" to "FAIL", "regression" to "PASS"),
            "trust-client-tenant" to mapOf("compile" to "PASS", "security" to "FAIL", "regression" to "PASS"),
            "tamper-test-framework" to mapOf("compile" to "PASS", "security" to "FAIL", "regression" to "PASS"),
            "compile-error" to mapOf("compile" to "FAIL"),
        )
        expected.forEach { (name, gates) ->
            val evaluation = grade(name).second
            assertEquals("FAIL", evaluation["verdict"].asString(), "$name: $evaluation")
            assertEquals("NOT_VERIFIED", evaluation["patchGate"].asString(), name)
            assertEquals(gates, gates(evaluation), name)
            assertNoHiddenLeak(evaluation.toString())
        }
    }

    @Test
    fun `only allowed paths, PATCH-capable modes and canonical bundles are accepted`() {
        val learner = fixtures.learner()
        val session = session(learner)
        val reference = TenantOrders.patch("reference")
        listOf(
            mapOf("tests/test_hidden.py" to "assert True\n"), mapOf("app/server.py" to "print('x')\n"), mapOf("../app/authz.py" to "x = 1\n"),
            mapOf("/app/app/authz.py" to "x = 1\n"), reference + mapOf("app/data.py" to "x = 1\n"),
        ).forEach { files ->
            val refused = submitPatch(learner, session, files)
            assertEquals(422, refused.status, "${files.keys}: ${refused.body}")
            assertFalse(files.keys.any { it in refused.body }, "error responses do not echo submitted paths")
        }
        assertEquals(422, submitPatch(learner, session, emptyMap()).status)
        val unsupported = submitPatch(learner, session(learner, "CTF"), reference)
        assertEquals("UNSUPPORTED_MODE", json.readTree(unsupported.body)["code"].asString(), "a CTF Session takes no patches")
        assertEquals(404, submitPatch(fixtures.learner(), session, reference).status, "another learner's Session looks missing")

        // The bundle digest depends on content, not on member order; it is stored with the learner's own artifact.
        val first = UUID.fromString(json.readTree(submitPatch(learner, session, reference).body)["id"].asString())
        val second = UUID.fromString(json.readTree(submitPatch(learner, session, reference.toSortedMap(compareByDescending { it })).body)["id"].asString())
        val digest = { id: UUID -> fixtures.string("SELECT safe_metadata->>'bundleDigest' FROM submissions WHERE id = ?", id) }
        assertEquals(digest(first), digest(second))
        assertEquals("LEARNER", fixtures.string("SELECT a.sensitivity FROM artifacts a JOIN submissions s ON s.artifact_id = a.id WHERE s.id = ?", first))
        publisher.publishOnce()
        fixtures.await { fixtures.string("SELECT state FROM jobs WHERE submission_id = ?", second) == "DISPATCHED" }
        assertNull(FakeGradingWorker(jobs).runOnce(), "generic workers never grade patches")
    }

    @Test
    fun `platform errors and partial results end as SYSTEM_ERROR and INCONCLUSIVE, never FAIL`() {
        val learner = fixtures.learner()
        val session = session(learner)
        val broken = dispatched(submitPatch(learner, session, TenantOrders.patch("reference")))
        val (failing, _) = agent { _, _ -> PatchObservation(PatchRunOutcome.PLATFORM_ERROR, false, emptyMap(), null) }
        repeat(3) {
            assertNotNull(failing.runOnce())
            clock.advance(Duration.ofMinutes(10))
            jobs.sweepOnce()
        }
        assertEquals("SYSTEM_ERROR", fixtures.string("SELECT verdict FROM evaluations WHERE submission_id = ? AND is_active", broken))
        assertEquals("INCONCLUSIVE", fixtures.string("SELECT patch_gate FROM evaluations WHERE submission_id = ? AND is_active", broken))

        val other = fixtures.learner()
        val partial = dispatched(submitPatch(other, session(other), TenantOrders.patch("reference")))
        val (lossy, control) = agent { _, _ -> PatchObservation(PatchRunOutcome.COMPLETED, true, mapOf("own-tenant-read" to true), "0".repeat(64)) }
        val assignment = assertNotNull(lossy.runOnce())
        assertEquals("RETRY_WAIT", fixtures.string("SELECT state FROM jobs WHERE submission_id = ?", partial), "a partial report is collector loss")
        assertEquals(Ack.STALE, control.patchResult(assignment.lease, PatchObservation(PatchRunOutcome.COMPLETED, true, emptyMap(), null)), "duplicate results change nothing")
        assertEquals(0, fixtures.count("SELECT count(*) FROM evaluations WHERE submission_id = ?", partial))
    }

    @Test
    fun `missing grader material is a content error without retry`() {
        val learner = fixtures.learner()
        // A version with a patch section but no private grading material (fixture versions have no oracle blob).
        val session = fixtures.activeSession(learner.userId, """{"modes":["PATCH"],"patch":{"language":"Python","allowedPaths":["app/authz.py"],"maxFiles":10}}""", "PATCH")
        val submission = dispatched(submitPatch(learner, session, mapOf("app/authz.py" to "x = 1\n")))
        assertNull(agent().first.runOnce(), "nothing gradable is handed out")
        assertEquals("FAILED", fixtures.string("SELECT state FROM jobs WHERE submission_id = ?", submission))
        assertEquals(1, fixtures.count("SELECT attempt FROM jobs WHERE submission_id = ?", submission))
        assertEquals("SYSTEM_ERROR", fixtures.string("SELECT verdict FROM evaluations WHERE submission_id = ? AND is_active", submission))
        assertEquals("INCONCLUSIVE", fixtures.string("SELECT patch_gate FROM evaluations WHERE submission_id = ? AND is_active", submission))
    }
}
