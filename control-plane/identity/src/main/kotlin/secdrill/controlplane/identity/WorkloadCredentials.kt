package secdrill.controlplane.identity

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import secdrill.kernel.WorkloadKind
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/** An authenticated runner or gateway on `/internal` routes. Never valid on learner or operator routes. */
data class WorkloadPrincipal(val runnerId: String, val kind: WorkloadKind)

/**
 * Short-lived workload credentials (11, 19), hashed at rest and bound to one runner id and kind. They stand in for
 * the mTLS workload identity of 15/19 until node enrollment exists (D-17); runners still never get DB credentials.
 */
@Service
class WorkloadCredentialService(private val jdbc: JdbcClient, private val clock: Clock) {
    companion object {
        val MAX_TTL: Duration = Duration.ofHours(24)
        private val RUNNER_ID = Regex("^[a-z0-9-]{3,64}$")
    }

    private fun now() = clock.instant().atOffset(ZoneOffset.UTC)

    fun issue(runnerId: String, kind: WorkloadKind, ttl: Duration): String {
        require(RUNNER_ID.matches(runnerId)) { "invalid runner id" }
        require(!ttl.isNegative && !ttl.isZero && ttl <= MAX_TTL) { "workload credential TTL must be within $MAX_TTL" }
        val raw = Secrets.newToken()
        jdbc.sql("INSERT INTO runner_credentials(token_hash, runner_id, kind, issued_at, expires_at) VALUES (?, ?, ?, ?, ?)")
            .params(Secrets.hash(raw), runnerId, kind.name, now(), now().plus(ttl)).update()
        return raw
    }

    fun authenticate(raw: String?): WorkloadPrincipal? {
        if (raw.isNullOrEmpty()) return null
        return jdbc.sql("SELECT runner_id, kind FROM runner_credentials WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?")
            .params(Secrets.hash(raw), now())
            .query { rs, _ -> WorkloadPrincipal(rs.getString(1), WorkloadKind.valueOf(rs.getString(2))) }
            .optional().orElse(null)
    }

    /** Node quarantine (19, 25): revokes every credential of a runner so it cannot claim or report anything. */
    fun revokeRunner(runnerId: String) {
        jdbc.sql("UPDATE runner_credentials SET revoked_at = ? WHERE runner_id = ? AND revoked_at IS NULL").params(now(), runnerId).update()
    }
}
