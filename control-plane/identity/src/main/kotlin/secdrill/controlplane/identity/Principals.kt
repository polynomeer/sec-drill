package secdrill.controlplane.identity

import secdrill.kernel.OperatorRole
import secdrill.kernel.UserId
import java.time.Instant
import java.util.UUID

/** An authenticated learner request. Carries the CSRF hash of its login for the CSRF check. */
data class LearnerPrincipal(
    val userId: UserId,
    val authSessionId: UUID,
    val accessExpiresAt: Instant,
    internal val csrfHash: String,
) {
    /**
     * Re-authentication proof for a sensitive action (deletion): the caller must present the live login's CSRF
     * token. A real re-auth challenge replaces this when an identity provider is wired (D-09, ADR 0013).
     */
    fun confirms(confirmationToken: String?): Boolean = Secrets.matches(confirmationToken, csrfHash)
}

/** An authenticated operator request on `/ops` routes. Never valid on learner routes. */
data class OperatorPrincipal(
    val operatorId: UUID,
    val role: OperatorRole,
    val purpose: String,
)
