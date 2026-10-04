package secdrill.controlplane.platform

import org.slf4j.LoggerFactory
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.core.MessageDeliveryMode
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import secdrill.kernel.EventType
import secdrill.kernel.Rfc3339
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Writes event envelopes (contracts/event.schema.json) into the Outbox inside the caller's transaction (16). */
@Component
class OutboxWriter(private val jdbc: JdbcClient, private val json: JsonMapper, private val clock: Clock) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun append(
        type: EventType,
        aggregateId: UUID,
        aggregateVersion: Long,
        sessionId: UUID,
        correlationId: UUID,
        payload: Map<String, Any?>,
        seq: Long? = null,
        causationId: UUID? = null,
    ): UUID {
        val eventId = UUID.randomUUID()
        val now = clock.instant()
        val envelope = buildMap<String, Any?> {
            put("eventId", eventId.toString())
            put("type", type.name)
            put("schemaVersion", 1)
            put("aggregateId", aggregateId.toString())
            put("aggregateVersion", aggregateVersion)
            put("sessionId", sessionId.toString())
            seq?.let { put("seq", it) }
            put("occurredAt", Rfc3339.format(now))
            put("correlationId", correlationId.toString())
            causationId?.let { put("causationId", it.toString()) }
            put("payload", payload)
        }
        jdbc.sql(
            """INSERT INTO outbox_events(id, aggregate_id, aggregate_version, event_type, envelope, created_at, next_attempt_at)
               VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)""",
        ).params(eventId, aggregateId, aggregateVersion, type.name, json.writeValueAsString(envelope), now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC))
            .update()
        return eventId
    }
}

/**
 * Publishes Outbox rows to RabbitMQ with publisher confirms (ADR 0004). A row is marked published only after the
 * broker acked it and did not return it as unroutable. Any failure keeps the row and schedules a retry with backoff,
 * so a broker outage after commit never loses an accepted event. Delivery is at-least-once; consumers use the inbox.
 */
@Component
class OutboxPublisher(
    private val jdbc: JdbcClient,
    private val rabbit: RabbitTemplate,
    private val transactions: TransactionTemplate,
    private val properties: AsyncProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${secdrill.async.publisher-interval:500ms}")
    fun scheduled() {
        if (properties.schedulingEnabled) publishOnce()
    }

    /** Publishes one batch of due rows. Returns how many were confirmed. */
    fun publishOnce(): Int = transactions.execute {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val due = jdbc.sql(
            """SELECT id, event_type, envelope::text, publish_attempts FROM outbox_events
               WHERE published_at IS NULL AND next_attempt_at <= ? ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED""",
        ).params(now, properties.publisherBatchSize).query { rs, _ ->
            Due(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getInt(4))
        }.list()
        var confirmed = 0
        for (row in due) {
            val error = publish(row)
            if (error == null) {
                jdbc.sql("UPDATE outbox_events SET published_at = ?, publish_attempts = publish_attempts + 1, last_publish_error = NULL WHERE id = ?")
                    .params(now, row.id).update()
                confirmed++
            } else {
                val backoff = Duration.ofSeconds(1L shl minOf(row.attempts, 10)).coerceAtMost(properties.publishBackoffMax)
                jdbc.sql("UPDATE outbox_events SET publish_attempts = publish_attempts + 1, next_attempt_at = ?, last_publish_error = ? WHERE id = ?")
                    .params(now.plus(backoff), error.take(500), row.id).update()
            }
        }
        confirmed
    } ?: 0

    private fun publish(row: Due): String? = try {
        val message = MessageBuilder.withBody(row.envelope.toByteArray(Charsets.UTF_8))
            .setContentType("application/json")
            .setMessageId(row.id.toString())
            .setType(row.type)
            .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
            .build()
        val correlation = CorrelationData(row.id.toString())
        rabbit.send(RabbitTopology.EVENTS_EXCHANGE, row.type, message, correlation)
        val confirm = correlation.future.get(properties.confirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
        when {
            correlation.returned != null -> "unroutable: ${correlation.returned?.replyText}"
            !confirm.ack -> "nack: ${confirm.reason}"
            else -> null
        }
    } catch (error: Exception) {
        log.warn("Outbox publish failed for {}: {}", row.id, error.toString())
        error.javaClass.simpleName + ": " + (error.message ?: "")
    }

    private data class Due(val id: UUID, val type: String, val envelope: String, val attempts: Int)
}
