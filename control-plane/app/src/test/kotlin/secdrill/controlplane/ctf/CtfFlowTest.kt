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
import secdrill.content.SyntheticBundles
import secdrill.controlplane.catalog.ContentPublishingTest
import secdrill.controlplane.catalog.ContentTestSupport
import secdrill.controlplane.evidence.ArtifactService
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.identity.WorkloadCredentialService
import secdrill.controlplane.lab.FakeRuntime
import secdrill.controlplane.lab.LabCalls
import secdrill.controlplane.lab.LabService
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
import secdrill.execution.agent.HttpLabControl
import secdrill.execution.agent.LocalTrustedDockerAdapter
import secdrill.execution.agent.ObjectiveObserver
import secdrill.execution.agent.RunnerAgent
import secdrill.execution.agent.RuntimeAdapter
import secdrill.execution.fake.FakeGradingWorker
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.LabAction
import secdrill.execution.protocol.ObjectiveObservation
import secdrill.gateway.GatewayConfig
import secdrill.gateway.LabGateway
import secdrill.kernel.ConnectTokens
import secdrill.kernel.JobKind
import secdrill.kernel.LabTerminateReason
import secdrill.kernel.OperatorRole
import secdrill.kernel.WorkloadKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T07 first CTF vertical (prompt 08) over the real API, Lab Gateway and internal runner API.
 *
 * The full flow runs the synthetic tenant-orders image on the `local-trusted` Docker profile: that is not strong
 * isolation, so every result here is a demo result and the tests assert that it is labelled so. Grading edge cases
 * use the in-memory runtime.
 */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["test.context=ctf-flow", "secdrill.lab.gateway-base-url=http://gateway.invalid"])
class CtfFlowTest {
    companion object {
        const val RUNNER = "runner-ctf"
        const val FAKE_RUNNER = "runner-ctf-fake"
        const val PYTHON = "python@sha256:f6a589d43c42b9e7f7dc67a12d37132491f362859a5d750607710cc56da3bc72"
        private val gatewayKeys = ConnectTokens.generate()
        private val FLAG = Regex("SD\\{[A-Za-z0-9_-]{20,}}")

        val labImage: String get() = TenantOrders.image

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
            registry.add("secdrill.lab.connect-signing-key") { gatewayKeys.first }
        }
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var workloads: WorkloadCredentialService
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var jobs: JobLeaseService
    @Autowired private lateinit var labs: LabService
    @Autowired private lateinit var flags: FlagService
    @Autowired private lateinit var artifacts: ArtifactService
    @Autowired private lateinit var store: ArtifactStore
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private lateinit var browser: TestBrowser
    private lateinit var docker: LocalTrustedDockerAdapter
    private lateinit var gateway: LabGateway
    private lateinit var gatewayBase: String

    private data class Content(val scenarioId: UUID, val versionId: UUID, val challengeId: UUID, val bundle: secdrill.content.ContentBundle)

    private val content: Content by lazy { publish() }

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").update()
        jdbc.sql(
            """UPDATE labs SET state = 'TERMINATED', desired_state = 'TERMINATED', terminate_reason = coalesce(terminate_reason, 'OPERATOR'),
               terminate_requested_at = coalesce(terminate_requested_at, now()), cleanup_confirmed_at = now(), cleanup_receipt = '{}' WHERE cleanup_confirmed_at IS NULL""",
        ).update()
        docker = LocalTrustedDockerAdapter(RUNNER, "test-ownership-key-not-a-secret".toByteArray(), LocalTrustedIsolationTest.IMAGE, relayImage = PYTHON)
        gateway = LabGateway(
            GatewayConfig(0, "http://127.0.0.1:$port", workloads.issue("gateway-ctf", WorkloadKind.GATEWAY, Duration.ofHours(1)),
                ConnectTokens.publicKey(gatewayKeys.second), Regex("^http://127\\.0\\.0\\.1:\\d+$")),
            clock,
        )
        gatewayBase = "http://127.0.0.1:${gateway.start()}"
    }

    @AfterTest
    fun tearDown() {
        gateway.stop()
        fun docker(vararg args: String) = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start().let { it.inputStream.readAllBytes().toString(Charsets.UTF_8).also { _ -> it.waitFor() } }
        docker("ps", "-aq", "--filter", "label=secdrill.runner=$RUNNER").lines().filter { it.isNotBlank() }.forEach { docker("rm", "-f", it) }
        docker("network", "ls", "-q", "--filter", "label=secdrill.runner=$RUNNER").lines().filter { it.isNotBlank() }.forEach { docker("network", "rm", it) }
    }

    private fun publish(): Content {
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author synthetic CTF", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review synthetic CTF", Duration.ofHours(1))
        var bundle = SyntheticBundles.withManifest(SyntheticBundles.valid()) {
            it.put("title", "Synthetic tenant order leak")
            (it["runtime"] as ObjectNode).put("imageDigest", labImage).put("memoryMiB", 256).put("vcpus", 1)
        }
        bundle = SyntheticBundles.withOracle(bundle) {
            (it["verifier"] as ObjectNode).set("requires", json.valueToTree(listOf("actorTenant != resourceTenant", "syntheticOrderReturned", "sessionChallengeBound")))
        }
        val registered = ContentTestSupport.register(browser, author, bundle)
        assertEquals(201, registered.status, registered.body)
        val version = UUID.fromString(json.readTree(registered.body)["scenarioVersionId"].asString())
        assertEquals(201, ContentTestSupport.validate(browser, author, version).status)
        assertEquals(204, ContentTestSupport.approve(browser, reviewer, version).status)
        val manifest = bundle.manifest
        return Content(UUID.fromString(manifest["scenarioId"].asString()), version, UUID.fromString(manifest["challenges"][0]["id"].asString()), bundle)
    }

    // --- learner API ---------------------------------------------------------------------------------------------

    private fun send(learner: Fixtures.Learner, method: String, path: String, body: String? = null, key: String? = UUID.randomUUID().toString()): TestResponse =
        browser.send(method, path, body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies,
            headers = key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())

    private fun createSession(learner: Fixtures.Learner): UUID {
        val created = send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"${content.versionId}","mode":"CTF"}""")
        assertEquals(201, created.status, created.body)
        assertEquals("CREATED", json.readTree(created.body)["status"].asString())
        return UUID.fromString(json.readTree(created.body)["id"].asString())
    }

    private fun session(learner: Fixtures.Learner, id: UUID): JsonNode = json.readTree(send(learner, "GET", "/v1/sessions/$id", key = null).body)
    private fun version(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()

    private fun submitFlag(learner: Fixtures.Learner, session: UUID, flag: String, key: String = UUID.randomUUID().toString()): TestResponse =
        send(learner, "POST", "/v1/sessions/$session/submissions",
            """{"kind":"FLAG","expectedVersion":${version(session)},"content":{"challengeId":"${content.challengeId}","flag":"$flag"}}""", key)

    private fun dispatched(submission: UUID): UUID {
        val job = UUID.fromString(fixtures.string("SELECT id::text FROM jobs WHERE submission_id = ?", submission))
        publisher.publishOnce()
        fixtures.await { fixtures.string("SELECT state FROM jobs WHERE id = ?", job) == "DISPATCHED" }
        return job
    }

    private fun submissionView(learner: Fixtures.Learner, submission: UUID): JsonNode =
        json.readTree(send(learner, "GET", "/v1/submissions/$submission", key = null).body)

    private data class Runner(val labs: RunnerAgent, val grades: GradeAgent, val control: HttpGradeControl)

    private fun runner(id: String, runtime: RuntimeAdapter): Runner {
        val credential = workloads.issue(id, WorkloadKind.AGENT, Duration.ofHours(1))
        val grades = HttpGradeControl("http://127.0.0.1:$port", credential)
        return Runner(RunnerAgent(HttpLabControl("http://127.0.0.1:$port", credential), runtime), GradeAgent(grades, ObjectiveObserver(runtime)), grades)
    }

    private fun readyLab(learner: Fixtures.Learner, session: UUID, runner: Runner): UUID {
        val requested = LabCalls.requestLab(browser, learner, session, version(session))
        assertEquals(202, requested.status, requested.body)
        val lab = UUID.fromString(json.readTree(requested.body)["id"].asString())
        generateSequence { runner.labs.runOnce() }.first { it.lab.labId == lab }
        assertEquals("READY", fixtures.string("SELECT state FROM labs WHERE id = ?", lab))
        return lab
    }

    // --- Lab Gateway (the learner's browser on the Lab origin) -----------------------------------------------------

    private fun gatewayCookie(learner: Fixtures.Learner, session: UUID, lab: UUID): String {
        val connect = LabCalls.connect(browser, learner, session, lab)
        assertEquals(200, connect.status, connect.body)
        val url = json.readTree(connect.body)["connectUrl"].asString().replace("http://gateway.invalid", gatewayBase)
        val response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(303, response.statusCode())
        return response.headers().allValues("Set-Cookie").single { it.startsWith("${LabGateway.COOKIE}=") }.substringBefore(";")
    }

    private fun lab(cookie: String, method: String, path: String, body: String? = null, token: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(gatewayBase + path)).header("Cookie", cookie)
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.header("Content-Type", "application/json") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun oracleValues(): List<String> =
        listOf("hidden-direct-other-tenant", "hidden-own-tenant-read", "mutant-deny-everything", "mutant-fix-direct-route-only",
            "secret-ref:content/synthetic-orders", "--- synthetic reference patch ---", "actorTenant != resourceTenant")

    @Test
    fun `tenant leak flow - the objective is confirmed independently, labelled demo, and the Lab is reclaimed`(output: CapturedOutput) {
        val runner = runner(RUNNER, docker)
        val learner = fixtures.learner()
        val sessionId = createSession(learner)
        val labId = readyLab(learner, sessionId, runner)
        val view = session(learner, sessionId)
        assertEquals("ACTIVE", view["status"].asString())
        assertFalse(view["lab"]["isolationVerified"].asBoolean(), "local-trusted is never verified isolation")

        // Synthetic target through the gateway: own tenant works, the other tenant's order leaks the Session flag.
        val cookie = gatewayCookie(learner, sessionId, labId)
        val login = lab(cookie, "POST", "/api/login", """{"username":"alice","password":"lab-alice"}""")
        assertEquals(200, login.statusCode(), login.body())
        val token = json.readTree(login.body())["token"].asString()
        val mine = json.readTree(lab(cookie, "GET", "/api/orders", token = token).body())
        assertEquals(listOf(1001, 1002), mine["orders"].values().map { it["id"].asInt() }, "owner access shows only the own tenant")
        assertEquals(401, lab(cookie, "GET", "/api/orders/1003").statusCode())
        val leaked = lab(cookie, "GET", "/api/orders/1003", token = token)
        val flag = assertNotNull(FLAG.find(leaked.body())?.value, "the other tenant's order carries the flag")
        assertEquals(404, LabCalls.connect(browser, fixtures.learner(), sessionId, labId).status, "another learner cannot open this Lab")

        // Submit; the same Idempotency-Key replays the same submission.
        val key = UUID.randomUUID().toString()
        val body = """{"kind":"FLAG","expectedVersion":${version(sessionId)},"content":{"challengeId":"${content.challengeId}","flag":"$flag"}}"""
        val accepted = send(learner, "POST", "/v1/sessions/$sessionId/submissions", body, key)
        assertEquals(202, accepted.status, accepted.body)
        val submission = UUID.fromString(json.readTree(accepted.body)["id"].asString())
        assertEquals(accepted.body, send(learner, "POST", "/v1/sessions/$sessionId/submissions", body, key).body, "the same key replays the first response")
        assertEquals(1, fixtures.count("SELECT count(*) FROM submissions WHERE session_id = ?", sessionId))

        dispatched(submission)
        assertNull(FakeGradingWorker(jobs).runOnce(), "a generic worker never grades flags")
        val assignment = assertNotNull(runner.grades.runOnce())
        assertEquals(labId, assignment.objective?.labId, "the runner observes the Lab the flag came from")
        val evaluation = submissionView(learner, submission)["evaluation"]
        assertEquals("PASS", evaluation["verdict"].asString(), evaluation.toString())
        assertEquals(mapOf("flag" to "PASS", "objective" to "PASS"), evaluation["gates"].values().associate { it["key"].asString() to it["result"].asString() })
        assertTrue(evaluation["demo"].asBoolean(), "unverified isolation makes it a demo result")
        assertEquals("ctf-objective/1", evaluation["policyVersion"].asString())
        val schemas = json.readTree(Path.of(System.getProperty("secdrill.contracts.dir"), "openapi.yaml").toFile())["components"]["schemas"]
        fun conforms(name: String, node: JsonNode) {
            val schema = schemas[name]
            assertTrue(schema["properties"].propertyNames().containsAll(node.propertyNames()), "$name has undeclared fields: ${node.propertyNames()}")
            assertTrue(node.propertyNames().containsAll(schema["required"].values().map { it.asString() }), "$name misses required fields: ${node.propertyNames()}")
        }
        conforms("Evaluation", evaluation)
        conforms("Submission", submissionView(learner, submission))
        conforms("Session", session(learner, sessionId))
        conforms("Lab", session(learner, sessionId)["lab"])
        assertEquals(1, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'OBJECTIVE_CONFIRMED' AND trust_level = 'OBSERVED' AND source = 'COLLECTOR'", sessionId))

        // A duplicate result for the same lease changes nothing.
        assertEquals(Ack.STALE, runner.control.observed(assignment.lease, ObjectiveObservation(false, 0, null)))
        assertEquals(1, fixtures.count("SELECT count(*) FROM evaluations WHERE submission_id = ?", submission))
        assertEquals(404, browser.send("GET", "/v1/submissions/$submission", cookies = fixtures.learner().cookies).status, "results are owner-only")

        // The raw flag is in no table, receipt, response replay or log; the oracle is in no learner-facing output.
        listOf(
            "SELECT count(*) FROM submissions WHERE safe_metadata::text LIKE ?", "SELECT count(*) FROM evidence WHERE safe_payload::text LIKE ?",
            "SELECT count(*) FROM outbox_events WHERE envelope::text LIKE ?", "SELECT count(*) FROM idempotency_records WHERE response_body::text LIKE ?",
            "SELECT count(*) FROM audit_events WHERE action LIKE ?", "SELECT count(*) FROM evaluations WHERE gates::text LIKE ?",
            "SELECT count(*) FROM labs WHERE cleanup_receipt::text LIKE ? OR endpoint LIKE ?",
        ).forEach { sql -> assertEquals(0, fixtures.count(sql, *Array(sql.count { it == '?' }) { "%$flag%" }), sql) }
        val receipt = artifacts.readInternal(UUID.fromString(fixtures.string("SELECT artifact_id::text FROM submissions WHERE id = ?", submission))!!)!!
        assertFalse(flag in receipt.bytes.toString(Charsets.UTF_8), "the receipt holds the match, not the flag")
        assertFalse(flag in output.all, "no log line contains the flag")
        val learnerFacing = listOf(
            send(learner, "GET", "/v1/scenarios/${content.scenarioId}", key = null).body, session(learner, sessionId).toString(),
            submissionView(learner, submission).toString(), lab(cookie, "GET", "/", token = token).body(), leaked.body(),
        )
        learnerFacing.forEach { body -> oracleValues().forEach { assertFalse(it in body, "oracle value '$it' reached learner-facing output") } }

        // Finish: completion gate met, Session SUBMITTED, Lab reclaimed with a receipt and nothing left in Docker.
        val finished = send(learner, "POST", "/v1/sessions/$sessionId/finish", """{"expectedVersion":${version(sessionId)}}""", key = null)
        assertEquals(202, finished.status, finished.body)
        assertEquals("SUBMITTED", json.readTree(finished.body)["status"].asString())
        assertEquals(403, lab(cookie, "GET", "/api/orders", token = token).statusCode(), "the gateway stops admitting at once")
        assertEquals(LabAction.CLEANUP, generateSequence { runner.labs.runOnce() }.first { it.lab.labId == labId }.action)
        assertEquals("TERMINATED", fixtures.string("SELECT state FROM labs WHERE id = ?", labId))
        val cleanup = fixtures.string("SELECT cleanup_receipt::text FROM labs WHERE id = ?", labId)!!
        listOf("container:lab-$labId-1", "container:lab-$labId-1-relay", "network:lab-$labId-1", "network:lab-$labId-1-in")
            .forEach { assertTrue(it in cleanup, "$it missing from $cleanup") }
        assertTrue(docker.list().none { it.labId == labId }, "no runtime remains")
        assertEquals(409, submitFlag(learner, sessionId, flag).status, "a finished Session takes no submissions")
    }

    @Test
    fun `flags of another Session or of a terminated Lab never match`() {
        val fake = FakeRuntime()
        val runner = runner(FAKE_RUNNER, fake)
        val alice = fixtures.learner()
        val bob = fixtures.learner()
        val aliceSession = createSession(alice)
        val bobSession = createSession(bob)
        val aliceLab = readyLab(alice, aliceSession, runner)
        readyLab(bob, bobSession, runner)
        val aliceFlag = flagOf(aliceSession, aliceLab)

        val foreign = UUID.fromString(json.readTree(submitFlag(bob, bobSession, aliceFlag).body)["id"].asString())
        dispatched(foreign)
        assertNotNull(runner.grades.runOnce())
        val rejected = submissionView(bob, foreign)["evaluation"]
        assertEquals("FAIL", rejected["verdict"].asString(), "another Session's flag is a wrong flag")
        assertEquals("FAIL", rejected["gates"][0]["result"].asString())

        labs.requestTermination(aliceLab, LabTerminateReason.OPERATOR)
        val stale = UUID.fromString(json.readTree(submitFlag(alice, aliceSession, aliceFlag).body)["id"].asString())
        dispatched(stale)
        assertNotNull(runner.grades.runOnce())
        assertEquals("FAIL", submissionView(alice, stale)["evaluation"]["verdict"].asString(), "a terminated Lab's flag no longer matches")
    }

    @Test
    fun `a correct flag whose access was not observed is inconclusive, never FAIL`() {
        val runner = runner(FAKE_RUNNER, FakeRuntime()) // no access records: nothing qualifying is observed
        val learner = fixtures.learner()
        val sessionId = createSession(learner)
        val labId = readyLab(learner, sessionId, runner)
        val submission = UUID.fromString(json.readTree(submitFlag(learner, sessionId, flagOf(sessionId, labId)).body)["id"].asString())
        dispatched(submission)
        assertNotNull(runner.grades.runOnce())
        val view = submissionView(learner, submission)
        assertEquals("SYSTEM_ERROR", view["evaluation"]["verdict"].asString())
        assertEquals(mapOf("flag" to "PASS", "objective" to "INCONCLUSIVE"), view["evaluation"]["gates"].values().associate { it["key"].asString() to it["result"].asString() })
        assertEquals("EVALUATION_FAILED", view["status"].asString())
        val finish = send(learner, "POST", "/v1/sessions/$sessionId/finish", """{"expectedVersion":${version(sessionId)}}""", key = null)
        assertEquals(409, finish.status)
        assertEquals("objective_confirmed", json.readTree(finish.body)["details"]["missingGates"][0].asString())
    }

    @Test
    fun `a tampered receipt ends as SYSTEM_ERROR after retries, never FAIL`() {
        val runner = runner(FAKE_RUNNER, FakeRuntime())
        val learner = fixtures.learner()
        val sessionId = createSession(learner)
        val labId = readyLab(learner, sessionId, runner)
        val submission = UUID.fromString(json.readTree(submitFlag(learner, sessionId, flagOf(sessionId, labId)).body)["id"].asString())
        val job = dispatched(submission)
        val (artifactId, objectKey) = jdbc.sql("SELECT a.id, a.object_key FROM artifacts a JOIN submissions s ON s.artifact_id = a.id WHERE s.id = ?")
            .param(submission).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.single()
        val forged = String(store.get(objectKey)!!).replace("\"matched\":true", "\"matched\":false").toByteArray()
        store.put(objectKey, forged)
        jdbc.sql("UPDATE artifacts SET digest = ?, byte_size = ? WHERE id = ?").params(secdrill.kernel.Digests.sha256Hex(forged), forged.size, artifactId).update()

        repeat(3) {
            assertNotNull(runner.grades.runOnce())
            clock.advance(Duration.ofMinutes(10))
            jobs.sweepOnce()
        }
        assertEquals("FAILED", fixtures.string("SELECT state FROM jobs WHERE id = ?", job))
        // Read from the database: the learner's 15-minute access session expired while the clock moved on.
        assertEquals("SYSTEM_ERROR", fixtures.string("SELECT verdict FROM evaluations WHERE submission_id = ? AND is_active", submission))
        assertTrue(fixtures.count("SELECT count(*) FROM audit_events WHERE purpose = 'flag receipt invalid' AND action LIKE ?", "%$submission%") >= 1)
    }

    @Test
    fun `wrong flags are limited to 10 per minute with Retry-After`() {
        val learner = fixtures.learner()
        val sessionId = createSession(learner)
        readyLab(learner, sessionId, runner(FAKE_RUNNER, FakeRuntime()))
        repeat(10) { assertEquals(202, submitFlag(learner, sessionId, "SD{wrong-guess-$it-aaaaaaaaaaaaaaaaaaaa}").status) }
        val limited = submitFlag(learner, sessionId, "SD{wrong-guess-final-aaaaaaaaaaaaaaaaaaa}")
        assertEquals(429, limited.status)
        assertEquals("RATE_LIMITED", json.readTree(limited.body)["code"].asString())
        assertEquals("60", limited.headers.firstValue("Retry-After").orElse(null))
        val unknown = send(learner, "POST", "/v1/sessions/$sessionId/submissions",
            """{"kind":"FLAG","expectedVersion":${version(sessionId)},"content":{"challengeId":"${UUID.randomUUID()}","flag":"x"}}""")
        assertEquals(422, unknown.status, "only flag challenges of the pinned version are accepted")
    }

    @Test
    fun `sessions pin a published version and refuse modes it does not offer`() {
        val learner = fixtures.learner()
        val unsupported = send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"${content.versionId}","mode":"WARGAME"}""")
        assertEquals(422, unsupported.status)
        assertEquals("UNSUPPORTED_MODE", json.readTree(unsupported.body)["code"].asString())
        assertEquals(404, send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"${UUID.randomUUID()}","mode":"CTF"}""").status)
        val second = publish()
        val page = json.readTree(send(learner, "GET", "/v1/scenarios?mode=CTF&limit=1", key = null).body)
        assertEquals(1, page["items"].size())
        val catalog = generateSequence(page) { previous ->
            previous["nextCursor"]?.takeIf { it.isString }?.asString()?.let { json.readTree(send(learner, "GET", "/v1/scenarios?mode=CTF&limit=1&cursor=$it", key = null).body) }
        }.flatMap { it["items"].values() }.map { it["scenarioVersionId"].asString() }.toList()
        assertTrue(content.versionId.toString() in catalog && second.versionId.toString() in catalog, "paging reaches every published scenario")
        assertEquals(422, send(learner, "GET", "/v1/scenarios?mode=PURPLE&cursor=${page["nextCursor"].asString()}", key = null).status, "a cursor is bound to its filters")
        val sessionId = createSession(learner)
        assertEquals(content.versionId.toString(), session(learner, sessionId)["scenarioVersionId"].asString())
        assertEquals(404, browser.send("GET", "/v1/sessions/$sessionId", cookies = fixtures.learner().cookies).status)
    }

    /** The flag of a Lab generation, recomputed from its stored nonce (what the Lab's target data contains). */
    private fun flagOf(session: UUID, lab: UUID): String {
        val (nonce, keyVersion) = jdbc.sql("SELECT flag_nonce, flag_key_version FROM labs WHERE id = ?").param(lab).query { rs, _ -> rs.getBytes(1) to rs.getString(2) }.single()
        return flags.issue(session, content.challengeId, nonce, keyVersion)
    }
}
