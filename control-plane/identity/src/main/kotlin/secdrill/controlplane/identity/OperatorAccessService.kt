package secdrill.controlplane.identity

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import secdrill.kernel.AuditActorType
import secdrill.kernel.OperatorRole
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Operator bearer tokens and audit (12, 15, 19). Tokens are short-lived, purpose-bound and separate from
 * learner logins. Issuance has no HTTP endpoint yet; the operator console arrives with T14.
 */
@Service
class OperatorAccessService(private val jdbc: JdbcClient, private val clock: Clock) {
    companion object {
        val MAX_TTL: Duration = Duration.ofHours(12)
    }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    fun issue(operatorId: UUID, role: OperatorRole, purpose: String, ttl: Duration): String {
        require(!ttl.isNegative && !ttl.isZero && ttl <= MAX_TTL) { "operator token TTL must be within $MAX_TTL" }
        require(purpose.trim().length >= 3) { "operator token needs a purpose" }
        val raw = Secrets.newToken()
        val now = now()
        jdbc.sql("INSERT INTO operator_tokens(token_hash, operator_id, role, purpose, issued_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)")
            .params(Secrets.hash(raw), operatorId, role.name, purpose.trim(), now, now.plus(ttl)).update()
        return raw
    }

    fun authenticate(rawBearer: String?): OperatorPrincipal? {
        if (rawBearer.isNullOrEmpty()) return null
        return jdbc.sql(
            "SELECT operator_id, role, purpose FROM operator_tokens WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?",
        ).params(Secrets.hash(rawBearer), now())
            .query { rs, _ -> OperatorPrincipal(rs.getObject(1, UUID::class.java), OperatorRole.valueOf(rs.getString(2)), rs.getString(3)) }
            .optional().orElse(null)
    }

    fun revoke(rawBearer: String) {
        jdbc.sql("UPDATE operator_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")
            .params(now(), Secrets.hash(rawBearer)).update()
    }

    /** Append-only audit row. Callers must not proceed if this throws (fail closed). */
    fun audit(principal: OperatorPrincipal, action: String) {
        jdbc.sql("INSERT INTO audit_events(id, actor_type, actor_id, purpose, action, occurred_at) VALUES (?, ?, ?, ?, ?, ?)")
            .params(UUID.randomUUID(), AuditActorType.OPERATOR.name, principal.operatorId, principal.purpose, action.take(300), now())
            .update()
    }
}
