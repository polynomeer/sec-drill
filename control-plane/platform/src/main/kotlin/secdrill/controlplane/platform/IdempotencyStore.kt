package secdrill.controlplane.platform

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

sealed interface IdempotencyDecision {
    /** Same key, same canonical body: return the stored first response without re-running anything (15). */
    data class Replay(val status: Int, val body: String) : IdempotencyDecision
    /** Same key, different body: 409 IDEMPOTENCY_CONFLICT. */
    data object Conflict : IdempotencyDecision
    data object Proceed : IdempotencyDecision
}

/**
 * Idempotency-Key store keyed by owner + concrete route + key for 24 hours (15). [begin] takes a transaction-scoped
 * advisory lock, so concurrent requests with one key serialize and the loser sees the winner's record. Only
 * successful (2xx) responses are recorded; a rejected request can be retried with the same key.
 */
@Component
class IdempotencyStore(private val jdbc: JdbcClient, private val clock: Clock) {
    companion object {
        val RETENTION: Duration = Duration.ofHours(24)
    }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    @Transactional(propagation = Propagation.MANDATORY)
    fun begin(owner: UUID, route: String, key: UUID, requestDigest: String): IdempotencyDecision {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").param("$owner|$route|$key").query().singleRow()
        val record = jdbc.sql(
            "SELECT request_digest, response_status, response_body, expires_at FROM idempotency_records WHERE owner_id = ? AND route = ? AND idempotency_key = ?",
        ).params(owner, route, key).query { rs, _ ->
            Stored(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java))
        }.optional().orElse(null) ?: return IdempotencyDecision.Proceed

        if (!record.expiresAt.isAfter(now())) {
            jdbc.sql("DELETE FROM idempotency_records WHERE owner_id = ? AND route = ? AND idempotency_key = ?").params(owner, route, key).update()
            return IdempotencyDecision.Proceed
        }
        return if (record.digest == requestDigest) IdempotencyDecision.Replay(record.status, record.body) else IdempotencyDecision.Conflict
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(owner: UUID, route: String, key: UUID, requestDigest: String, status: Int, body: String) {
        require(status in 200..299) { "only successful responses are replayable" }
        val now = now()
        jdbc.sql(
            """INSERT INTO idempotency_records(owner_id, route, idempotency_key, request_digest, response_status, response_body, created_at, expires_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        ).params(owner, route, key, requestDigest, status, body, now, now.plus(RETENTION)).update()
    }

    private data class Stored(val digest: String, val status: Int, val body: String, val expiresAt: OffsetDateTime)
}
