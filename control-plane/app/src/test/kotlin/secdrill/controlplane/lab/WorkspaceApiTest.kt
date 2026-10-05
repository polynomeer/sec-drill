package secdrill.controlplane.lab

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
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.kernel.OperatorRole
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Workspace APIs added for T11: hints, the evidence stream cursor, Purple finish gates and linked Sessions. */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@TestPropertySource(properties = ["test.context=workspace-api"])
class WorkspaceApiTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
        }

        const val H1 = "Synthetic hint one: compare the tenant in the token with the order's tenant."
        const val H2 = "Synthetic hint two: the list route accepts a parameter it should not."
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private data class Content(val version: UUID, val challenge: UUID)
    private val content: Content by lazy { publish() }

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
    }

    private fun publish(): Content {
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author synthetic workspace", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review synthetic workspace", Duration.ofHours(1))
        var bundle = SyntheticBundles.withManifest(SyntheticBundles.valid()) {
            it.set("actions", json.valueToTree(listOf("REVOKE_TOKEN", "DISABLE_ENDPOINT", "ISOLATE_WORKLOAD", "ENABLE_AUDIT")))
            it.set("completionRequirements", json.readTree("""{"CTF":["objective_confirmed"],"PURPLE":["objective_confirmed","detection_evaluated","action_applied","patch_verified","postmortem_submitted"]}"""))
        }
        val challenge = UUID.fromString(bundle.manifest["challenges"][0]["id"].asString())
        bundle = SyntheticBundles.withOracle(bundle) {
            it.set("hints", json.valueToTree(listOf(mapOf("challengeId" to challenge.toString(), "level" to 1, "text" to H1), mapOf("challengeId" to challenge.toString(), "level" to 2, "text" to H2))))
        }
        val registered = ContentTestSupport.register(browser, author, bundle)
        assertEquals(201, registered.status, registered.body)
        val id = UUID.fromString(json.readTree(registered.body)["scenarioVersionId"].asString())
        val report = ContentTestSupport.validate(browser, author, id)
        assertEquals("PASS", json.readTree(report.body)["status"].asString(), report.body)
        assertEquals(204, ContentTestSupport.approve(browser, reviewer, id).status)
        return Content(id, challenge)
    }

    private fun send(learner: Fixtures.Learner, method: String, path: String, body: String? = null, key: String? = UUID.randomUUID().toString()): TestResponse =
        browser.send(method, path, body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies,
            headers = key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())

    private fun session(learner: Fixtures.Learner, mode: String, parent: UUID? = null): UUID {
        val parentField = parent?.let { ""","parentSessionId":"$it"""" } ?: ""
        val created = send(learner, "POST", "/v1/sessions", """{"scenarioVersionId":"${content.version}","mode":"$mode"$parentField}""")
        assertEquals(201, created.status, created.body)
        return UUID.fromString(json.readTree(created.body)["id"].asString()).also {
            jdbc.sql("UPDATE sessions SET status = 'ACTIVE' WHERE id = ?").param(it).update()
        }
    }

    private fun version(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()
    private fun hint(learner: Fixtures.Learner, session: UUID, level: Int, challenge: UUID = content.challenge) =
        send(learner, "POST", "/v1/sessions/$session/hints", """{"challengeId":"$challenge","level":$level}""", key = null)

    @Test
    fun `hints open in order, deduct cumulatively once, and never reach the ledger as text`() {
        val learner = fixtures.learner()
        val sessionId = session(learner, "CTF")
        assertEquals(422, hint(learner, sessionId, 2).status, "H2 needs H1 first")
        val first = json.readTree(hint(learner, sessionId, 1).body)
        assertEquals(H1, first["text"].asString())
        assertEquals(5, first["totalPointDeduction"].asInt())
        assertEquals(5, json.readTree(hint(learner, sessionId, 1).body)["totalPointDeduction"].asInt(), "asking again deducts nothing")
        assertEquals(15, json.readTree(hint(learner, sessionId, 2).body)["totalPointDeduction"].asInt())
        assertEquals(2, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'HINT_GRANTED'", sessionId))
        assertEquals(0, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND safe_payload::text LIKE ?", sessionId, "%Synthetic hint%"))
        assertEquals(422, hint(learner, sessionId, 3).status, "no H3 in this content")
        assertEquals(422, hint(learner, sessionId, 1, UUID.randomUUID()).status)
        assertEquals(404, hint(fixtures.learner(), sessionId, 1).status)
    }

    private fun events(learner: Fixtures.Learner, session: UUID, lastEventId: String?, wanted: Int): List<Pair<String, String>> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/v1/sessions/$session/stream"))
            .header("Cookie", learner.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }).timeout(Duration.ofSeconds(20)).GET()
        lastEventId?.let { request.header("Last-Event-ID", it) }
        val response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofLines())
        assertEquals(200, response.statusCode())
        val events = mutableListOf<Pair<String, String>>()
        var id: String? = null
        response.body().use { lines ->
            for (line in lines) {
                if (line.startsWith("id:")) id = line.removePrefix("id:").trim()
                if (line.startsWith("data:")) events += id!! to line.removePrefix("data:").trim()
                if (events.size >= wanted) break
            }
        }
        return events
    }

    @Test
    fun `the evidence stream resumes after the last seen sequence`() {
        val learner = fixtures.learner()
        val sessionId = session(learner, "CTF")
        hint(learner, sessionId, 1)
        hint(learner, sessionId, 2)
        val all = events(learner, sessionId, null, 3) // SessionCreated + two hints
        assertEquals(listOf("1", "2", "3"), all.map { it.first })
        val resumed = events(learner, sessionId, "2", 1)
        assertEquals("3", resumed.single().first, "a reconnect gets only what it missed")
        assertTrue("HINT_GRANTED" in resumed.single().second)
        assertEquals(400, browser.send("GET", "/v1/sessions/$sessionId/stream", cookies = learner.cookies, headers = mapOf("Last-Event-ID" to "x")).status)
        assertEquals(404, browser.send("GET", "/v1/sessions/$sessionId/stream", cookies = fixtures.learner().cookies).status)
    }

    @Test
    fun `Purple finish lists every missing gate from server evidence, and Purple after CTF is a new linked Session`() {
        val learner = fixtures.learner()
        val ctf = session(learner, "CTF")
        val purple = session(learner, "PURPLE", parent = ctf)
        assertEquals(ctf.toString(), fixtures.string("SELECT parent_session_id::text FROM sessions WHERE id = ?", purple))
        assertEquals("CTF", fixtures.string("SELECT mode FROM sessions WHERE id = ?", ctf), "the original Session is unchanged")
        val first = send(learner, "POST", "/v1/sessions/$purple/finish", """{"expectedVersion":${version(purple)}}""", key = null)
        assertEquals(409, first.status)
        assertEquals(setOf("objective_confirmed", "detection_evaluated", "action_applied", "patch_verified", "postmortem_submitted"),
            json.readTree(first.body)["details"]["missingGates"].values().map { it.asString() }.toSet())
        assertEquals(200, send(learner, "POST", "/v1/sessions/$purple/actions", """{"type":"ENABLE_AUDIT","parameters":{"source":"APPLICATION"},"expectedVersion":${version(purple)}}""").status)
        val second = send(learner, "POST", "/v1/sessions/$purple/finish", """{"expectedVersion":${version(purple)}}""", key = null)
        assertFalse("action_applied" in json.readTree(second.body)["details"]["missingGates"].values().map { it.asString() }, "the applied action now counts")
        assertEquals(404, send(fixtures.learner(), "POST", "/v1/sessions", """{"scenarioVersionId":"${content.version}","mode":"PURPLE","parentSessionId":"$ctf"}""").status,
            "only the owner's Session can be a parent")
    }
}
