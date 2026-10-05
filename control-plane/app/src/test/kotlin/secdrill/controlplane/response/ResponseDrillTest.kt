package secdrill.controlplane.response

import org.springframework.beans.factory.annotation.Autowired
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
import secdrill.controlplane.platform.OutboxPublisher
import secdrill.controlplane.submission.DetectionGradingService
import secdrill.controlplane.submission.JobLeaseService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.execution.fake.FakeGradingWorker
import secdrill.kernel.OperatorRole
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
 * T10 detection and incident response over the real API (prompt 10). Everything here is a model on synthetic data:
 * actions are SIMULATED, detection is scored on synthetic logs, explanations are USER_REPORTED.
 */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@TestPropertySource(properties = ["test.context=response-drill"])
class ResponseDrillTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
        }

        const val BEHAVIOUR = """{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"and","children":[
            {"op":"neq","field":"tenantId","compareField":"resourceTenantId"},{"op":"eq","field":"status","value":200}]}}"""
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var jobs: JobLeaseService
    @Autowired private lateinit var detection: DetectionGradingService
    @Autowired private lateinit var response: IncidentResponseService
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private val version: UUID by lazy { publish() }

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").update()
    }

    private fun publish(): UUID {
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author synthetic drill", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review synthetic drill", Duration.ofHours(1))
        var bundle = SyntheticBundles.withManifest(SyntheticBundles.valid()) {
            it.set("actions", json.valueToTree(listOf("REVOKE_TOKEN", "DISABLE_ENDPOINT", "ISOLATE_WORKLOAD", "ENABLE_AUDIT")))
            it.set("phases", json.valueToTree(listOf("ANALYZE", "ATTACK", "OBSERVE", "DETECT", "CONTAIN", "PATCH", "VERIFY", "POSTMORTEM")))
        }
        bundle = SyntheticBundles.withOracle(bundle) {
            it.set("detection", json.readTree("""{"groundTruthUnit":"attackEpisode","normalWindowSeconds":300,"minRecallBps":9000,"minPrecisionBps":8000,"maxP95LatencySeconds":30,"attackLabelVisibleToRule":false}"""))
        }
        val registered = ContentTestSupport.register(browser, author, bundle)
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
        jdbc.sql("UPDATE sessions SET status = 'ACTIVE' WHERE id = ?").param(id).update()
        return id
    }

    private fun version(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()

    private fun act(learner: Fixtures.Learner, session: UUID, type: String, parameter: String, target: String, key: String = UUID.randomUUID().toString(), expected: Long? = null) =
        send(learner, "POST", "/v1/sessions/$session/actions",
            """{"type":"$type","parameters":{"$parameter":"$target"},"expectedVersion":${expected ?: version(session)}}""", key)

    private fun state(response: TestResponse): JsonNode = json.readTree(response.body).also { assertEquals(200, response.status, response.body) }["state"]

    @Test
    fun `model actions are SIMULATED, reproducible and refuse conflicts`() {
        val learner = fixtures.learner()
        val sessionId = session(learner)
        val audit = act(learner, sessionId, "ENABLE_AUDIT", "source", "APPLICATION")
        val body = json.readTree(audit.body)
        assertEquals("SIMULATED", body["representation"].asString(), "a model action is never shown as a real Lab change")
        assertEquals(1, state(audit)["tick"].asInt())
        assertEquals(1.0, state(audit)["evidenceCoverage"].asDouble())

        val key = UUID.randomUUID().toString()
        val revoke = act(learner, sessionId, "REVOKE_TOKEN", "tokenId", "tok-sync", key)
        val revoked = state(revoke)
        assertTrue(revoked["workloadSuccess"].asDouble() < 1.0, "the shared automation token breaks normal work")
        assertEquals(revoke.body, act(learner, sessionId, "REVOKE_TOKEN", "tokenId", "tok-sync", key, expected = version(sessionId) - 1).body, "same key replays")

        val again = act(learner, sessionId, "REVOKE_TOKEN", "tokenId", "tok-sync")
        assertEquals(409, again.status, "a repeated action has no effect and is refused")
        assertEquals(422, act(learner, sessionId, "ISOLATE_WORKLOAD", "workloadId", "no-such-workload").status)
        assertEquals(422, send(learner, "POST", "/v1/sessions/$sessionId/actions", """{"type":"REBOOT","parameters":{},"expectedVersion":${version(sessionId)}}""").status)
        assertEquals(409, act(learner, sessionId, "DISABLE_ENDPOINT", "routeGroup", "orders", expected = 0).status, "stale expectedVersion")
        assertEquals(2, fixtures.count("SELECT count(*) FROM applied_actions WHERE session_id = ?", sessionId), "refused actions are not recorded")

        assertEquals(2, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'ActionApplied' AND source = 'SIMULATOR' AND trust_level = 'SIMULATED'", sessionId))
        assertEquals(0, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'ActionApplied' AND trust_level <> 'SIMULATED'", sessionId))
        assertTrue(response.verifyReplay(sessionId), "replaying seed + actions reproduces every recorded state digest")
        jdbc.sql("UPDATE applied_actions SET target = 'tok-bob' WHERE session_id = ? AND seq = 2").param(sessionId).update()
        assertFalse(response.verifyReplay(sessionId), "a changed action history no longer matches its digests")

        assertEquals("UNSUPPORTED_MODE", json.readTree(act(learner, session(learner, "CTF"), "ENABLE_AUDIT", "source", "AUTH").body)["code"].asString())
        assertEquals(404, act(fixtures.learner(), sessionId, "ENABLE_AUDIT", "source", "AUTH").status)
    }

    private fun submitRule(learner: Fixtures.Learner, session: UUID, rule: String): TestResponse =
        send(learner, "POST", "/v1/sessions/$session/submissions",
            """{"kind":"DETECTION","expectedVersion":${version(session)},"content":{"rule":$rule,"explanation":"Synthetic reasoning."}}""")

    private fun graded(learner: Fixtures.Learner, session: UUID, rule: String): JsonNode {
        val accepted = submitRule(learner, session, rule)
        assertEquals(202, accepted.status, accepted.body)
        val submission = UUID.fromString(json.readTree(accepted.body)["id"].asString())
        publisher.publishOnce()
        fixtures.await { fixtures.string("SELECT j.state FROM jobs j WHERE j.submission_id = ?", submission) == "DISPATCHED" }
        assertNull(FakeGradingWorker(jobs).runOnce(), "generic workers never grade detection rules")
        assertNotNull(detection.runOnce())
        return json.readTree(send(learner, "GET", "/v1/submissions/$submission", key = null).body)["evaluation"]
    }

    private fun dimensions(evaluation: JsonNode) = evaluation["dimensions"].values().associate { it["key"].asString() to it["score"].asDouble() }

    @Test
    fun `detection rules are scored on a hidden renamed holdout, with N A left out`() {
        val learner = fixtures.learner()
        val sessionId = session(learner)
        val dataset = json.readTree(send(learner, "GET", "/v1/sessions/$sessionId/detection-dataset", key = null).body)
        assertEquals("TRAINING", dataset["variant"].asString())
        assertEquals("SIMULATED", dataset["representation"].asString())
        val fields = dataset["events"].values().flatMap { it.propertyNames() }.toSet()
        assertEquals(setOf("eventId", "time", "eventType", "actorId", "tenantId", "resourceTenantId", "status", "routeGroup"), fields, "no labels")

        val good = graded(learner, sessionId, BEHAVIOUR)
        assertEquals("PASS", good["verdict"].asString(), good.toString())
        assertEquals("PASS", good["gates"][0]["result"].asString())
        assertEquals(100.0, dimensions(good)["holdout.recall"])

        // The training attacker's name works on training only: the holdout renames every actor.
        val attacker = dataset["events"].values().filter { it["tenantId"].asString() != it["resourceTenantId"].asString() }
            .groupBy { it["actorId"].asString() }.maxBy { it.value.size }.key
        val memorized = graded(learner, sessionId, """{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"eq","field":"actorId","value":"$attacker"}}""")
        assertEquals("FAIL", memorized["verdict"].asString())
        assertEquals(100.0, dimensions(memorized)["training.recall"], "looks perfect on training")
        assertEquals(0.0, dimensions(memorized)["holdout.recall"])

        val silent = graded(learner, sessionId, """{"op":"eq","field":"eventType","value":"never.happens"}""")
        assertEquals("FAIL", silent["verdict"].asString(), "every episode missed")
        assertFalse("holdout.precision" in dimensions(silent) || "holdout.f1" in dimensions(silent), "N/A is left out, not shown as 0")

        val deep = (1..9).fold("""{"op":"eq","field":"status","value":200}""") { inner, _ -> """{"op":"and","children":[$inner,{"op":"eq","field":"status","value":200}]}""" }
        val refused = submitRule(learner, sessionId, deep)
        assertEquals(422, refused.status)
        assertTrue("deeper" in refused.body)
        assertEquals(422, submitRule(learner, sessionId, """{"op":"eq","field":"attackLabel","value":"attack"}""").status, "labels are not fields")
        assertEquals("UNSUPPORTED_MODE", json.readTree(submitRule(learner, session(learner, "CTF"), BEHAVIOUR).body)["code"].asString())
    }

    @Test
    fun `simulated, observed and user-reported evidence stay apart`() {
        val learner = fixtures.learner()
        val sessionId = session(learner)
        act(learner, sessionId, "ENABLE_AUDIT", "source", "APPLICATION")
        graded(learner, sessionId, BEHAVIOUR)
        val levels = jdbc.sql("SELECT event_type, source, trust_level FROM evidence WHERE session_id = ?").param(sessionId)
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.list()
        assertTrue(Triple("ActionApplied", "SIMULATOR", "SIMULATED") in levels)
        assertTrue(Triple("TEST_RESULT", "SIMULATOR", "SIMULATED") in levels, "detection is scored on synthetic logs")
        assertTrue(Triple("HYPOTHESIS_REPORTED", "USER", "USER_REPORTED") in levels, "the learner's explanation is a claim")
        assertTrue(levels.none { it.third == "OBSERVED" }, "nothing here was observed in a real Lab")
    }
}
