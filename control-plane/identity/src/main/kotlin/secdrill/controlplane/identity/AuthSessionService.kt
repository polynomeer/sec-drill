package secdrill.controlplane.identity

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.kernel.AuthRevokeReason
import secdrill.kernel.AuthTokenKind
import secdrill.kernel.UserId
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Raw secrets for the browser. They exist only in this value and the Set-Cookie headers. */
data class IssuedLogin(
    val authSessionId: UUID,
    val accessToken: String,
    val refreshToken: String,
    val csrfToken: String?,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant,
)

sealed interface RefreshOutcome {
    data class Rotated(val login: IssuedLogin) : RefreshOutcome
    /** A superseded refresh token came back: the whole login was revoked (15). */
    data object Reused : RefreshOutcome
    data object Rejected : RefreshOutcome
}

/**
 * Platform login sessions (15, ADR 0002): opaque access and refresh tokens stored as SHA-256 hashes,
 * refresh rotation with reuse detection, and immediate rejection after revocation (12).
 */
@Service
class AuthSessionService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val properties: AuthProperties,
) {
    private fun now(): Instant = clock.instant()
    private fun Instant.db(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    @Transactional
    fun start(userId: UserId): IssuedLogin {
        val now = now()
        val id = UUID.randomUUID()
        val csrf = Secrets.newToken()
        jdbc.sql("INSERT INTO auth_sessions(id, user_id, csrf_hash, created_at) VALUES (?, ?, ?, ?)")
            .params(id, userId.value, Secrets.hash(csrf), now.db()).update()
        return issueTokens(id, now).copy(csrfToken = csrf)
    }

    fun authenticate(rawAccess: String?): LearnerPrincipal? {
        if (rawAccess.isNullOrEmpty()) return null
        return jdbc.sql(
            """SELECT s.user_id, s.id, s.csrf_hash, t.expires_at FROM auth_tokens t JOIN auth_sessions s ON s.id = t.auth_session_id
               WHERE t.token_hash = ? AND t.kind = 'ACCESS' AND t.superseded_at IS NULL AND t.expires_at > ? AND s.revoked_at IS NULL""",
        ).params(Secrets.hash(rawAccess), now().db())
            .query { rs, _ ->
                LearnerPrincipal(
                    userId = UserId(rs.getObject(1, UUID::class.java)),
                    authSessionId = rs.getObject(2, UUID::class.java),
                    csrfHash = rs.getString(3),
                    accessExpiresAt = rs.getObject(4, OffsetDateTime::class.java).toInstant(),
                )
            }.optional().orElse(null)
    }

    @Transactional
    fun refresh(rawRefresh: String?): RefreshOutcome {
        if (rawRefresh.isNullOrEmpty()) return RefreshOutcome.Rejected
        val now = now()
        // Row lock serializes concurrent refreshes of the same token; the loser sees it superseded.
        val token = jdbc.sql(
            """SELECT t.auth_session_id, t.expires_at, t.superseded_at, s.revoked_at FROM auth_tokens t
               JOIN auth_sessions s ON s.id = t.auth_session_id WHERE t.token_hash = ? AND t.kind = 'REFRESH' FOR UPDATE OF t, s""",
        ).param(Secrets.hash(rawRefresh)).query { rs, _ ->
            RefreshRow(
                sessionId = rs.getObject(1, UUID::class.java),
                expiresAt = rs.getObject(2, OffsetDateTime::class.java).toInstant(),
                superseded = rs.getObject(3) != null,
                revoked = rs.getObject(4) != null,
            )
        }.optional().orElse(null) ?: return RefreshOutcome.Rejected

        if (token.revoked) return RefreshOutcome.Rejected
        if (token.superseded) {
            revoke(token.sessionId, AuthRevokeReason.REFRESH_REUSE)
            return RefreshOutcome.Reused
        }
        if (!token.expiresAt.isAfter(now)) return RefreshOutcome.Rejected

        jdbc.sql("UPDATE auth_tokens SET superseded_at = ? WHERE auth_session_id = ? AND superseded_at IS NULL")
            .params(now.db(), token.sessionId).update()
        return RefreshOutcome.Rotated(issueTokens(token.sessionId, now))
    }

    /** Idempotent: revoking an already revoked login keeps the first reason. */
    @Transactional
    fun revoke(authSessionId: UUID, reason: AuthRevokeReason) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = ?, revoke_reason = ? WHERE id = ? AND revoked_at IS NULL")
            .params(now().db(), reason.name, authSessionId).update()
    }

    private fun issueTokens(authSessionId: UUID, now: Instant): IssuedLogin {
        val access = Secrets.newToken()
        val refresh = Secrets.newToken()
        val accessExpires = now.plus(properties.accessTtl)
        val refreshExpires = now.plus(properties.refreshTtl)
        val insert = "INSERT INTO auth_tokens(token_hash, auth_session_id, kind, issued_at, expires_at) VALUES (?, ?, ?, ?, ?)"
        jdbc.sql(insert).params(Secrets.hash(access), authSessionId, AuthTokenKind.ACCESS.name, now.db(), accessExpires.db()).update()
        jdbc.sql(insert).params(Secrets.hash(refresh), authSessionId, AuthTokenKind.REFRESH.name, now.db(), refreshExpires.db()).update()
        return IssuedLogin(authSessionId, access, refresh, null, accessExpires, refreshExpires)
    }

    private data class RefreshRow(val sessionId: UUID, val expiresAt: Instant, val superseded: Boolean, val revoked: Boolean)
}
