package secdrill.controlplane.submission

import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import org.testcontainers.rabbitmq.RabbitMQContainer
import secdrill.controlplane.platform.OutboxPublisher
import secdrill.controlplane.platform.RabbitTopology
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Outbox publishing with broker confirms, consumer inbox and DLQ (16, ADR 0004). This class gets its own context and
 * broker because it pauses the broker.
 */
@IntegrationTest
@TestPropertySource(properties = ["secdrill.async.confirm-timeout=1s", "test.context=outbox-delivery"])
class OutboxDeliveryTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var rabbit: RabbitTemplate
    @Autowired private lateinit var broker: RabbitMQContainer
    @Autowired private lateinit var clock: MutableClock

    private val json = JsonMapper.builder().build()

    private fun accepted(): Pair<UUID, UUID> {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val response = TestBrowser("http://127.0.0.1:$port").send(
            "POST", "/v1/sessions/$session/submissions", body = Fixtures.flagBody(0), origin = TEST_ORIGIN, csrf = learner.login.csrfToken,
            cookies = learner.cookies, headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertEquals(202, response.status, response.body)
        val submission = UUID.fromString(json.readTree(response.body)["id"].asString())
        val job = UUID.fromString(fixtures.string("SELECT id::text FROM jobs WHERE submission_id = ?", submission))
        return submission to job
    }

    private fun published(submission: UUID) =
        fixtures.string("SELECT published_at::text FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SubmissionAccepted'", submission)

    private fun jobState(job: UUID) = fixtures.string("SELECT state FROM jobs WHERE id = ?", job)

    @Test
    fun `confirmed publish marks the row and the consumer dispatches the job once`() {
        val (submission, job) = accepted()
        publisher.publishOnce()
        assertNotNull(published(submission))
        fixtures.await { jobState(job) == "DISPATCHED" }
        assertEquals(1, fixtures.count("SELECT count(*) FROM consumer_inbox WHERE consumer = 'job-dispatcher' AND event_id = (SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SubmissionAccepted')", submission))
    }

    @Test
    fun `broker outage after commit keeps the outbox row until a later confirm`() {
        val (submission, job) = accepted()
        broker.dockerClient.pauseContainerCmd(broker.containerId).exec()
        try {
            publisher.publishOnce()
        } finally {
            broker.dockerClient.unpauseContainerCmd(broker.containerId).exec()
        }
        assertEquals(null, published(submission), "an unconfirmed event must stay in the outbox")
        val attempts = fixtures.count("SELECT publish_attempts FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SubmissionAccepted'", submission)
        assertEquals(1, attempts)
        assertTrue(jobState(job) in setOf("PENDING", "DISPATCHED"), "the accepted submission survives the outage")
        // A message already in the socket buffer may still reach the broker after the missed confirm, so the event
        // can arrive late and again after the retry. At-least-once plus the inbox must still change the job once.

        clock.advance(Duration.ofMinutes(2)) // past the publish backoff
        fixtures.await { publisher.publishOnce(); published(submission) != null }
        fixtures.await { jobState(job) == "DISPATCHED" }
        Thread.sleep(1000)
        assertEquals(1, fixtures.count("SELECT version FROM jobs WHERE id = ?", job), "dispatch must happen exactly once")
    }

    @Test
    fun `duplicate deliveries change the job once`() {
        val (submission, job) = accepted()
        publisher.publishOnce()
        fixtures.await { jobState(job) == "DISPATCHED" }
        val version = fixtures.count("SELECT version FROM jobs WHERE id = ?", job)
        val envelope = fixtures.string("SELECT envelope::text FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SubmissionAccepted'", submission)!!
        repeat(3) {
            rabbit.send(RabbitTopology.EVENTS_EXCHANGE, "SubmissionAccepted", MessageBuilder.withBody(envelope.toByteArray()).build())
        }
        Thread.sleep(1500)
        assertEquals(version, fixtures.count("SELECT version FROM jobs WHERE id = ?", job), "redelivered events must not repeat the change")
        assertEquals(1, fixtures.count("SELECT count(*) FROM consumer_inbox WHERE event_id = (SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type = 'SubmissionAccepted')", submission))
    }

    @Test
    fun `poison messages go to the dead letter queue`() {
        rabbit.send(RabbitTopology.EVENTS_EXCHANGE, "SubmissionAccepted", MessageBuilder.withBody("not json".toByteArray()).build())
        val schemaV2 = """{"eventId":"${UUID.randomUUID()}","type":"SubmissionAccepted","schemaVersion":2,"payload":{"jobId":"${UUID.randomUUID()}"}}"""
        rabbit.send(RabbitTopology.EVENTS_EXCHANGE, "SubmissionAccepted", MessageBuilder.withBody(schemaV2.toByteArray()).build())
        val received = (1..2).map { assertNotNull(rabbit.receive(RabbitTopology.DEAD_LETTER_QUEUE, 10_000), "message $it not dead-lettered") }
        assertTrue(received.any { String(it.body) == "not json" })
    }
}
