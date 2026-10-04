package secdrill.controlplane.platform

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import secdrill.kernel.AuditActorType
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * Named crash points inside transactions. Production does nothing; tests substitute a bean that throws at a point
 * to prove that a failure before commit leaves no partial state (26).
 */
@Component
class FaultPoints {
    fun reach(point: String) = onReach(point)
    protected open fun onReach(point: String) {}
}

/** Audit rows written by the platform itself, e.g. rejected stale job results (16, 20). */
@Component
class SystemAudit(private val jdbc: JdbcClient, private val clock: Clock) {
    companion object {
        val SYSTEM_ACTOR: UUID = UUID(0, 0)
    }

    fun record(purpose: String, action: String) {
        jdbc.sql("INSERT INTO audit_events(id, actor_type, actor_id, purpose, action, occurred_at) VALUES (?, ?, ?, ?, ?, ?)")
            .params(UUID.randomUUID(), AuditActorType.SYSTEM.name, SYSTEM_ACTOR, purpose, action.take(300), clock.instant().atOffset(ZoneOffset.UTC))
            .update()
    }
}

/**
 * Consumer inbox (16). Call inside the consumer's business transaction: only the first delivery of an event id
 * for a consumer returns true, so redelivered or duplicated messages cannot repeat the business change.
 */
@Component
class ConsumerInbox(private val jdbc: JdbcClient) {
    fun firstDelivery(consumer: String, eventId: UUID): Boolean =
        jdbc.sql("INSERT INTO consumer_inbox(consumer, event_id) VALUES (?, ?) ON CONFLICT DO NOTHING")
            .params(consumer, eventId).update() == 1
}
