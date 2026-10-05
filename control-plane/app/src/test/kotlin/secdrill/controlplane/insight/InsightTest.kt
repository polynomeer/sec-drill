package secdrill.controlplane.insight

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
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.kernel.OperatorRole
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** T12 reports, re-grades, replay and skills over the real API (prompt 12). Synthetic content and data only. */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@TestPropertySource(properties = ["test.context=insight"])
class InsightTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
        }

        const val RULE = """{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"and","children":[
            {"op":"neq","field":"tenantId","compareField":"resourceTenantId"},{"op":"eq","field":"status","value":200}]}}"""
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var detection: DetectionGradingService
    @Autowired private lateinit var reports: ReportWorker
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private data class Content(val version: UUID, val challenge: UUID)
    private val drill: Content by lazy { publish("drill", others = 2) }

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
    }

    private fun publish(tag: String, others: Int = 0): Content {
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author synthetic $tag", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review synthetic $tag", Duration.ofHours(1))
        fun one(title: String, tags: List<String>): Content {
            var bundle = SyntheticBundles.withManifest(SyntheticBundles.valid()) {
                it.put("title", title)
                it.set("competencyTags", json.valueToTree(tags))
                it.set("actions", json.valueToTree(listOf("REVOKE_TOKEN", "DISABLE_ENDPOINT", "ISOLATE_WORKLOAD", "ENABLE_AUDIT")))
                it.set("completionRequirements", json.readTree("""{"CTF":["objective_confirmed"],"PURPLE":["detection_evaluated","action_applied"]}"""))
            }
            val challenge = UUID.fromString(bundle.manifest["challenges"][0]["id"].asString())
            bundle = SyntheticBundles.withOracle(bundle) {
                it.set("detection", json.readTree("""{"groundTruthUnit":"attackEpisode","normalWindowSeconds":300,"minRecallBps":9000,"minPrecisionBps":8000,"maxP95LatencySeconds":30,"attackLabelVisibleToRule":false}"""))
                it.set("hints", json.valueToTree(listOf(mapOf("challengeId" to challenge.toString(), "level" to 1, "text" to "Synthetic hint."))))
            }
            val registered = ContentTestSupport.register(browser, author, bundle)
            assertEquals(201, registered.status, registered.body)
            val id = UUID.fromString(json.readTree(registered.body)["scenarioVersionId"].asString())
            assertEquals("PASS", json.readTree(ContentTestSupport.validate(browser, author, id).body)["status"].asString())
            assertEquals(204, ContentTestSupport.approve(browser, reviewer, id).status)
            return Content(id, challenge)
        }
        repeat(others) { one("Synthetic follow-up $it", listOf("AUTHORIZATION", "FORENSICS")) }
        return one("Synthetic drill $tag", listOf("DETECTION", "RESPONSE"))
    }

    private fun send(learner: Fixtures.Learner, method: String, path: String, body: String? = null, key: String? = UUID.randomUUID().toString()): TestResponse =
        browser.send(method, path, body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies,
            headers = key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())
    private fun get(learner: Fixtures.Learner, path: String): TestResponse = send(learner, "GET", path, key = null)
    private fun body(response: TestResponse): JsonNode = json.readTree(response.body).also { assertTrue(response.status in 200..299, response.body) }
    private fun version(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()

    private fun session(learner: Fixtures.Learner, mode: String, parent: UUID? = null): UUID {
        val parentField = parent?.let { ""","parentSessionId":"$it"""" } ?: ""
        val id = UUID.fromString(body(send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"${drill.version}","mode":"$mode"$parentField}"""))["id"].asString())
        jdbc.sql("UPDATE sessions SET status = 'ACTIVE' WHERE id = ?").param(id).update()
        return id
    }

    private fun gradeRule(learner: Fixtures.Learner, session: UUID): UUID {
        val submission = UUID.fromString(body(send(learner, "POST", "/v1/sessions/$session/submissions",
            """{"kind":"DETECTION","expectedVersion":${version(session)},"content":{"rule":$RULE,"explanation":"Synthetic reasoning."}}"""))["id"].asString())
        publisher.publishOnce()
        fixtures.await { fixtures.string("SELECT state FROM jobs WHERE submission_id = ? ORDER BY revision DESC LIMIT 1", submission) == "DISPATCHED" }
        assertTrue(detection.runOnce() != null)
        return submission
    }

    private fun act(learner: Fixtures.Learner, session: UUID, type: String, parameter: String, target: String) =
        body(send(learner, "POST", "/v1/sessions/$session/actions", """{"type":"$type","parameters":{"$parameter":"$target"},"expectedVersion":${version(session)}}"""))

    /** A finished Purple Session that continues a CTF Session in which the learner took a hint. */
    private fun finishedPurple(): Triple<Fixtures.Learner, UUID, UUID> {
        val learner = fixtures.learner()
        val ctf = session(learner, "CTF")
        body(send(learner, "POST", "/v1/sessions/$ctf/hints", """{"challengeId":"${drill.challenge}","level":1}""", key = null))
        val purple = session(learner, "PURPLE", parent = ctf)
        val rule = gradeRule(learner, purple)
        act(learner, purple, "ENABLE_AUDIT", "source", "APPLICATION")
        act(learner, purple, "DISABLE_ENDPOINT", "routeGroup", "orders")
        act(learner, purple, "DISABLE_ENDPOINT", "routeGroup", "orders-legacy")
        act(learner, purple, "REVOKE_TOKEN", "tokenId", "tok-bob")
        assertEquals(409, get(learner, "/v1/sessions/$purple/report").status, "no report before finish")
        assertEquals("SUBMITTED", body(send(learner, "POST", "/v1/sessions/$purple/finish", """{"expectedVersion":${version(purple)}}""", key = null))["status"].asString())
        assertTrue(reports.runOnce() != null)
        assertEquals("COMPLETED", fixtures.string("SELECT status FROM sessions WHERE id = ?", purple))
        return Triple(learner, purple, rule)
    }

    @Test
    fun `the report anchors every evaluated dimension in evidence, shows help and scope, and explains its recommendations`() {
        val (learner, purple, _) = finishedPurple()
        val report = body(get(learner, "/v1/sessions/$purple/report"))
        assertEquals(1, report["revision"].asInt())
        assertEquals("GUIDED", report["exposure"].asString(), "help in the parent Session carries over")
        val dimensions = report["dimensions"].values().associateBy { it["key"].asString() }
        assertEquals(listOf("attack", "observation", "detection", "response", "patch", "regression", "postmortem"), report["dimensions"].values().map { it["key"].asString() })
        assertEquals("PASS", dimensions.getValue("detection")["status"].asString())
        assertEquals("SIMULATED", dimensions.getValue("detection")["representation"].asString(), "detection runs on synthetic logs")
        assertEquals("SIMULATED", dimensions.getValue("response")["representation"].asString(), "response is a model recomputation")
        assertEquals("NOT_ATTEMPTED", dimensions.getValue("patch")["status"].asString())
        assertEquals("NOT_EVALUATED", dimensions.getValue("observation")["status"].asString())
        val sessionEvidence = jdbc.sql("SELECT id::text FROM evidence WHERE session_id = ?").param(purple).query(String::class.java).list().toSet()
        listOf("detection", "response").forEach { key ->
            val anchors = dimensions.getValue(key)["evidenceIds"].values().map { it.asString() }
            assertTrue(anchors.isNotEmpty() && sessionEvidence.containsAll(anchors), "$key anchors point at this Session's ledger")
        }
        assertTrue(report["scopeLimitations"].values().any { "SIMULATED" in it.asString() })
        assertTrue(report["scopeLimitations"].values().any { "도움" in it.asString() })
        val recommendations = report["recommendations"].values().toList()
        assertTrue(recommendations.size in 1..3)
        assertTrue(recommendations.all { it["reasons"].size() > 0 && it["terms"].has("evidenceGap") }, "every recommendation says why")
        assertTrue(recommendations.none { it["scenarioVersionId"].asString() == drill.version.toString() }, "not the scenario just finished")
        assertEquals(1, fixtures.count("SELECT count(*) FROM recommendations WHERE session_id = ? AND payload->>'sourceWatermark' IS NOT NULL", purple))
        assertEquals(404, browser.send("GET", "/v1/sessions/$purple/report", cookies = fixtures.learner().cookies).status)
    }

    @Test
    fun `a re-grade adds a new evaluation and report revision and keeps the old ones`() {
        val (learner, purple, rule) = finishedPurple()
        val first = body(get(learner, "/v1/sessions/$purple/report"))
        val operator = operators.issue(UUID.randomUUID(), OperatorRole.OPERATOR, "re-grade synthetic submission", Duration.ofHours(1))
        assertEquals(200, browser.send("POST", "/ops/v1/submissions/$rule/rejudge", bearer = operator, cookies = emptyMap()).status)
        assertTrue(detection.runOnce() != null)
        assertEquals(2, fixtures.count("SELECT count(*) FROM evaluations WHERE submission_id = ?", rule), "the earlier revision is kept")
        assertEquals(1, fixtures.count("SELECT count(*) FROM evaluations WHERE submission_id = ? AND is_active", rule))
        assertTrue(reports.runOnce() != null)
        val second = body(get(learner, "/v1/sessions/$purple/report"))
        assertEquals(2, second["revision"].asInt())
        assertTrue(second["changeReason"].asString().isNotBlank())
        assertNotEquals(first["evaluationRefs"], second["evaluationRefs"])
        assertEquals(first["evaluationRefs"], body(get(learner, "/v1/sessions/$purple/report?revision=1"))["evaluationRefs"], "revision 1 is unchanged")

        val skills = body(get(learner, "/v1/skills/me"))
        val detectionSkill = skills["skills"].values().single { it["key"].asString() == "DETECTION" }
        assertEquals(1, detectionSkill["sampleCount"].asInt(), "a re-grade replaces, it does not add a sample")
        assertEquals("UNKNOWN", detectionSkill["level"].asString())
        assertEquals("LOW", detectionSkill["confidence"].asString(), "confidence is reported separately from the level")
        assertEquals(9, skills["skills"].size())
        assertEquals("skill-v1", skills["policyVersion"].asString())
        assertEquals(422, get(learner, "/v1/skills/me?policyVersion=skill-v0").status)
    }

    @Test
    fun `replay seeks to any tick with the recorded digest and marks expired artifacts`() {
        val (learner, purple, rule) = finishedPurple()
        val manifest = body(get(learner, "/v1/sessions/$purple/replay"))
        assertEquals(4, manifest["lastTick"].asInt())
        assertEquals(listOf(3), manifest["checkpoints"].values().map { it["tick"].asInt() })
        for (tick in 0..4) {
            val state = body(get(learner, "/v1/sessions/$purple/replay/state?tick=$tick"))
            assertEquals("SIMULATED", state["representation"].asString())
            if (tick > 0) assertEquals(state["recordedDigest"].asString(), state["stateDigest"].asString(), "tick $tick: seek equals the recorded state")
            assertEquals(if (tick >= 3) 3 else 0, state["fromCheckpointTick"].asInt())
        }
        assertEquals(422, get(learner, "/v1/sessions/$purple/replay/state?tick=5").status)
        val chunkPath = manifest["chunks"][0]["downloadPath"].asString()
        val chunk = body(get(learner, chunkPath))
        assertEquals(manifest["chunks"][0]["digest"].asString(), chunk["digest"].asString())
        val levels = chunk["items"].values().map { it["trustLevel"].asString() }.toSet()
        assertTrue(levels.containsAll(setOf("SIMULATED", "USER_REPORTED", "SERVER_VERIFIED")), "recorded facts keep their trust level: $levels")

        jdbc.sql("UPDATE artifacts SET expires_at = '2000-01-01T00:00:00Z' WHERE id = (SELECT artifact_id FROM submissions WHERE id = ?)").param(rule).update()
        val after = body(get(learner, "/v1/sessions/$purple/replay"))
        assertTrue(after["gaps"].values().any { it["reason"].asString() == "ARTIFACT_EXPIRED" })
        val hypothesis = body(get(learner, after["chunks"][0]["downloadPath"].asString()))["items"].values().single { it["type"].asString() == "HYPOTHESIS_REPORTED" }
        assertEquals("EXPIRED", hypothesis["artifact"].asString())
        assertNotEquals(manifest["chunks"][0]["digest"].asString(), after["chunks"][0]["digest"].asString(), "the chunk digest reflects what is still available")
        assertEquals(422, get(learner, "/v1/sessions/$purple/replay?fromSeq=5&toSeq=2").status)
        assertEquals(404, browser.send("GET", "/v1/sessions/$purple/replay", cookies = fixtures.learner().cookies).status)
    }
}
