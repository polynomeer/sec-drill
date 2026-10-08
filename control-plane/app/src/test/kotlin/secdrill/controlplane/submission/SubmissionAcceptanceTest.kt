package secdrill.controlplane.submission

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.kernel.EvidenceChain
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Atomic submission acceptance and Idempotency-Key semantics (14, 15, 16; prompt 04 required checks). */
@IntegrationTest
class SubmissionAcceptanceTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private lateinit var learner: Fixtures.Learner
    private lateinit var session: UUID

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        learner = fixtures.learner()
        session = fixtures.activeSession(learner.userId)
    }

    private fun submit(body: String, key: String? = UUID.randomUUID().toString(), sessionId: UUID = session, who: Fixtures.Learner = learner): TestResponse {
        val headers = buildMap { key?.let { put("Idempotency-Key", it) } }
        return browser.send("POST", "/v1/sessions/$sessionId/submissions", body = body, origin = TEST_ORIGIN, csrf = who.login.csrfToken,
            cookies = who.cookies, headers = headers)
    }

    private fun rows(table: String) = when (table) {
        "outbox_events" -> fixtures.count("SELECT count(*) FROM outbox_events o JOIN submissions s ON s.id = o.aggregate_id WHERE s.session_id = ?", session)
        else -> fixtures.count("SELECT count(*) FROM $table WHERE session_id = ?", session)
    }

    private fun sessionVersion() = fixtures.count("SELECT version FROM sessions WHERE id = ?", session)

    @Test
    fun `a recorded postmortem is stored for review without a grade job`() {
        // 10, T14: POSTMORTEM has no auto-grader; a GRADE job would dangle forever and the content would be lost.
        // OBJECTIVE is verified by independent observation (WargameObjectiveTest, ADR 0014), not recorded-only.
        listOf("POSTMORTEM" to "POSTMORTEM_SUBMITTED").forEach { (kind, eventType) ->
            val response = submit("""{"kind":"$kind","expectedVersion":${sessionVersion()},"content":{"summary":"synthetic root cause"}}""")
            assertEquals(202, response.status, response.body)
            val id = UUID.fromString(json.readTree(response.body)["id"].asString())
            assertEquals("ACCEPTED", json.readTree(response.body)["status"].asString())
            assertEquals(0, fixtures.count("SELECT count(*) FROM jobs WHERE submission_id = ?", id), "$kind creates no grade job")
            assertEquals("LEARNER", fixtures.string("SELECT a.sensitivity FROM artifacts a JOIN submissions s ON s.artifact_id = a.id WHERE s.id = ?", id), "$kind content is recorded")
            assertEquals(1, fixtures.count("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = ? AND source = 'USER' AND trust_level = 'USER_REPORTED'", session, eventType))
            val envelope = json.readTree(fixtures.string("SELECT envelope::text FROM outbox_events WHERE aggregate_id = ?", id))
            assertEnvelopeMatchesContract(envelope)
            assertFalse(envelope["payload"].has("jobId") && !envelope["payload"]["jobId"].isNull, "$kind accepted event carries no jobId")
        }
        // The dangling-job sweeper flags nothing, because no grade job exists for recorded submissions.
        assertEquals(0, fixtures.count("SELECT count(*) FROM jobs WHERE session_id = ? AND kind = 'GRADE'", session))
    }

    @Test
    fun `accepted submission stores submission, job, evidence and outbox event together`() {
        val flag = "SYNTHETIC-FLAG-${UUID.randomUUID()}"
        val response = submit(Fixtures.flagBody(0, flag))
        assertEquals(202, response.status, response.body)
        val body = json.readTree(response.body)
        val submissionId = UUID.fromString(body["id"].asString())
        assertEquals("ACCEPTED", body["status"].asString())

        assertEquals(1, rows("submissions"))
        assertEquals("PENDING", fixtures.string("SELECT state FROM jobs WHERE submission_id = ? AND kind = 'GRADE'", submissionId))
        assertEquals(1, sessionVersion())
        assertEquals(EvidenceChain.GENESIS, fixtures.string("SELECT previous_hash FROM evidence WHERE session_id = ? AND seq = 1", session))

        val envelope = json.readTree(fixtures.string("SELECT envelope::text FROM outbox_events WHERE aggregate_id = ?", submissionId))
        assertEnvelopeMatchesContract(envelope)
        assertEquals(1, envelope["seq"].asInt())
        assertEquals(null, fixtures.string("SELECT published_at::text FROM outbox_events WHERE aggregate_id = ?", submissionId))

        // 15: the raw flag is checked in memory only and never persisted.
        listOf("submissions", "outbox_events", "evidence", "idempotency_records", "jobs").forEach { table ->
            assertEquals(0, fixtures.count("SELECT count(*) FROM $table t WHERE row_to_json(t)::text LIKE ?", "%$flag%"), "raw flag stored in $table")
        }
    }

    @Test
    fun `same key and same body replays the first response, even with members reordered`() {
        val key = UUID.randomUUID().toString()
        val first = submit(Fixtures.flagBody(0, "SYNTHETIC-A"), key)
        val reordered = """{"content":{"flag":"SYNTHETIC-A","challengeId":"10000000-0000-4000-8000-000000000003"},"expectedVersion":0,"kind":"FLAG"}"""
        val second = submit(reordered, key)
        assertEquals(202, second.status, second.body)
        assertEquals(first.body, second.body)
        assertEquals(1, rows("submissions"))
        assertEquals(1, sessionVersion(), "a replay must not run the CAS again")
    }

    @Test
    fun `same key with a different body is 409 and stores nothing`() {
        val key = UUID.randomUUID().toString()
        assertEquals(202, submit(Fixtures.flagBody(0, "SYNTHETIC-A"), key).status)
        val conflict = submit(Fixtures.flagBody(0, "SYNTHETIC-B"), key)
        assertEquals(409, conflict.status)
        assertEquals("IDEMPOTENCY_CONFLICT", json.readTree(conflict.body)["code"].asString())
        assertEquals(1, rows("submissions"))
        assertEquals(1, rows("outbox_events"))
    }

    @Test
    fun `stale expectedVersion is 409 with latestVersion and the key stays usable`() {
        assertEquals(202, submit(Fixtures.flagBody(0)).status)
        val key = UUID.randomUUID().toString()
        val stale = submit(Fixtures.flagBody(0, "SYNTHETIC-C"), key)
        assertEquals(409, stale.status)
        val error = json.readTree(stale.body)
        assertEquals("VERSION_CONFLICT", error["code"].asString())
        assertEquals(1, error["details"]["latestVersion"].asInt())
        assertEquals(1, rows("submissions"))
        assertEquals(202, submit(Fixtures.flagBody(1, "SYNTHETIC-C"), key).status, "failed requests are not recorded for replay")
    }

    @Test
    fun `concurrent requests with one key create one submission`() {
        val key = UUID.randomUUID().toString()
        val body = Fixtures.flagBody(0, "SYNTHETIC-RACE")
        val pool = Executors.newFixedThreadPool(4)
        val results = try {
            pool.invokeAll((1..4).map { Callable { submit(body, key) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertTrue(results.all { it.status == 202 }, results.map { it.status }.toString())
        assertEquals(1, results.map { it.body }.toSet().size)
        assertEquals(1, rows("submissions"))
    }

    @Test
    fun `input, ownership and protection errors`() {
        val stranger = fixtures.learner()
        assertEquals(404, submit(Fixtures.flagBody(0), who = stranger).status, "other owner's session must look missing")
        assertEquals(404, submit(Fixtures.flagBody(0), sessionId = UUID.randomUUID()).status)
        assertEquals(400, submit(Fixtures.flagBody(0), key = null).status)
        assertEquals(400, submit(Fixtures.flagBody(0), key = "not-a-uuid").status)
        val invalid = submit("""{"kind":"FLAG","expectedVersion":0,"content":{"challengeId":"x","flag":""},"extra":1}""")
        assertEquals(422, invalid.status)
        val fields = json.readTree(invalid.body)["details"]["fieldErrors"].values().map { it["field"].asString() }.toSet()
        assertEquals(setOf("extra", "content.challengeId", "content.flag"), fields)
        assertEquals(422, submit("""{"kind":"PATCH","expectedVersion":0,"content":{"ratio":0.5}}""").status, "non-integer numbers are outside the canonical subset")
        assertEquals(413, submit("""{"kind":"POSTMORTEM","expectedVersion":0,"content":{"text":"${"x".repeat(270_000)}"}}""").status)
        val noCsrf = browser.send("POST", "/v1/sessions/$session/submissions", body = Fixtures.flagBody(0), origin = TEST_ORIGIN,
            cookies = learner.cookies, headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
        assertEquals(403, noCsrf.status)
        assertEquals(0, rows("submissions"))
        assertEquals(0, sessionVersion())
    }

    /** Checks the envelope against event.schema.json's required fields and the SubmissionAccepted payload rule. */
    private fun assertEnvelopeMatchesContract(envelope: JsonNode) {
        val schema = json.readTree(Path.of(System.getProperty("secdrill.contracts.dir"), "event.schema.json").toFile())
        val required = schema["required"].values().map { it.asString() }.toSet()
        assertTrue(envelope.propertyNames().toSet().containsAll(required), envelope.toString())
        assertTrue(schema["properties"].propertyNames().toSet().containsAll(envelope.propertyNames().toSet()), "unknown envelope field")
        val rule = schema["allOf"].values().first { it["if"]["properties"]["type"]["const"].asString() == "SubmissionAccepted" }
        val payloadSchema = rule["then"]["properties"]["payload"]
        val payloadKeys = envelope["payload"].propertyNames().toSet()
        assertTrue(payloadKeys.containsAll(payloadSchema["required"].values().map { it.asString() }), "missing required payload field: $payloadKeys")
        assertTrue(payloadSchema["properties"].propertyNames().toSet().containsAll(payloadKeys), "unknown payload field: $payloadKeys")
        assertFalse(envelope["payload"]["bundleRef"].has("content"))
    }
}
