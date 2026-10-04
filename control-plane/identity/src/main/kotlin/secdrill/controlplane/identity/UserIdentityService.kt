package secdrill.controlplane.identity

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.kernel.UserId
import java.util.UUID

/** Maps an external identity (issuer, subject) to a platform user. No email or profile claim is stored (NFR-03). */
@Service
class UserIdentityService(private val jdbc: JdbcClient) {
    @Transactional
    fun findOrCreate(issuer: String, subject: String): UserId {
        find(issuer, subject)?.let { return it }
        val candidate = UUID.randomUUID()
        jdbc.sql("INSERT INTO users(id, pseudonym) VALUES (?, ?)")
            .params(candidate, "learner-" + candidate.toString().take(8)).update()
        val inserted = jdbc.sql(
            "INSERT INTO user_identities(issuer, subject, user_id) VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING",
        ).params(issuer, subject, candidate).update()
        if (inserted == 1) return UserId(candidate)
        // A concurrent first login won; drop our unused user row and use theirs.
        jdbc.sql("DELETE FROM users WHERE id = ?").param(candidate).update()
        return checkNotNull(find(issuer, subject)) { "identity vanished after conflict" }
    }

    private fun find(issuer: String, subject: String): UserId? =
        jdbc.sql("SELECT user_id FROM user_identities WHERE issuer = ? AND subject = ?")
            .params(issuer, subject).query(UUID::class.java).optional().map(::UserId).orElse(null)
}
