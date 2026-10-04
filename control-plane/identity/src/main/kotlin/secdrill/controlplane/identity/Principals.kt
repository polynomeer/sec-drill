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
)

/** An authenticated operator request on `/ops` routes. Never valid on learner routes. */
data class OperatorPrincipal(
    val operatorId: UUID,
    val role: OperatorRole,
    val purpose: String,
)
