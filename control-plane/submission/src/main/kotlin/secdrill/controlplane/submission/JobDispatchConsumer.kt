package secdrill.controlplane.submission

import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpRejectAndDontRequeueException
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import secdrill.controlplane.platform.ConsumerInbox
import secdrill.controlplane.platform.RabbitTopology
import secdrill.kernel.EventType
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * Consumes SubmissionAccepted and makes the GRADE job claimable (PENDING -> DISPATCHED). The inbox row and the job
 * change commit together before the ack, so duplicates and redeliveries change nothing (16). Poison messages
 * (unknown type, wrong schema version, bad ids) go straight to the DLQ; transient failures are requeued until the
 * queue's delivery limit dead-letters them.
 */
@Component
class JobDispatchConsumer(
    private val jdbc: JdbcClient,
    private val inbox: ConsumerInbox,
    private val transactions: TransactionTemplate,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    companion object {
        const val CONSUMER = "job-dispatcher"
    }

    private val log = LoggerFactory.getLogger(javaClass)

    @RabbitListener(queues = [RabbitTopology.GRADING_QUEUE])
    fun onMessage(message: Message) {
        val (eventId, jobId) = parse(message)
        transactions.executeWithoutResult {
            if (!inbox.firstDelivery(CONSUMER, eventId)) return@executeWithoutResult
            jdbc.sql("UPDATE jobs SET state = 'DISPATCHED', dispatched_at = ?, version = version + 1 WHERE id = ? AND state = 'PENDING'")
                .params(clock.instant().atOffset(ZoneOffset.UTC), jobId).update()
        }
    }

    private fun parse(message: Message): Pair<UUID, UUID> {
        val envelope: JsonNode = runCatching { json.readTree(message.body) }.getOrNull() ?: poison("not JSON")
        if (envelope["type"]?.asString() != EventType.SubmissionAccepted.name) poison("unexpected type ${envelope["type"]}")
        if (envelope["schemaVersion"]?.asInt() != 1) poison("unsupported schemaVersion")
        val eventId = uuid(envelope["eventId"]) ?: poison("eventId")
        val jobId = uuid(envelope["payload"]?.get("jobId")) ?: poison("payload.jobId")
        return eventId to jobId
    }

    private fun uuid(node: JsonNode?): UUID? = node?.takeIf { it.isString }?.let { runCatching { Uuids.parse(it.asString()) }.getOrNull() }

    private fun poison(reason: String): Nothing {
        log.warn("Dead-lettering message on {}: {}", RabbitTopology.GRADING_QUEUE, reason)
        throw AmqpRejectAndDontRequeueException("poison message: $reason")
    }
}
