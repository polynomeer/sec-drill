package secdrill.controlplane.identity.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.identity.OperatorPrincipal
import secdrill.controlplane.identity.RefreshOutcome
import secdrill.controlplane.identity.UserIdentityService
import secdrill.kernel.AuthRevokeReason
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AuthSessionView(val userId: UUID, val accessExpiresAt: Instant)

@RestController
class AuthController(
    private val sessions: AuthSessionService,
    private val errors: ErrorEnvelopeWriter,
    private val clock: Clock,
) {
    @GetMapping("/v1/auth/session")
    fun session(@AuthenticationPrincipal principal: LearnerPrincipal) =
        AuthSessionView(principal.userId.value, principal.accessExpiresAt)

    @PostMapping("/v1/auth/refresh")
    fun refresh(request: HttpServletRequest, response: HttpServletResponse) {
        when (val outcome = sessions.refresh(AuthCookies.read(request, AuthCookies.REFRESH))) {
            is RefreshOutcome.Rotated -> {
                AuthCookies.write(response, outcome.login, clock)
                response.status = 204
            }
            RefreshOutcome.Reused, RefreshOutcome.Rejected -> {
                AuthCookies.clear(response)
                errors.unauthenticated(response)
            }
        }
    }

    @PostMapping("/v1/auth/logout")
    fun logout(@AuthenticationPrincipal principal: LearnerPrincipal, response: HttpServletResponse): ResponseEntity<Void> {
        sessions.revoke(principal.authSessionId, AuthRevokeReason.LOGOUT)
        AuthCookies.clear(response)
        return ResponseEntity.noContent().build()
    }
}

data class DevLoginRequest(val subject: String? = null)

/** Local development login without an identity provider. Registered only when `secdrill.auth.dev-login.enabled`. */
@RestController
@ConditionalOnProperty("secdrill.auth.dev-login.enabled", havingValue = "true")
class DevLoginController(
    private val identities: UserIdentityService,
    private val sessions: AuthSessionService,
    private val clock: Clock,
) {
    companion object {
        const val ISSUER = "urn:secdrill:dev-login"
    }

    @PostMapping("/v1/auth/dev-login")
    fun login(@RequestBody(required = false) body: DevLoginRequest?, response: HttpServletResponse): ResponseEntity<Void> {
        val subject = body?.subject?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: UUID.randomUUID().toString()
        AuthCookies.write(response, sessions.start(identities.findOrCreate(ISSUER, subject)), clock)
        return ResponseEntity.noContent().build()
    }
}

data class OperatorView(val operatorId: UUID, val role: String, val purpose: String)

/** Minimal operator endpoint proving the separate chain; real operations arrive with T14. */
@RestController
class OperatorController {
    @GetMapping("/ops/v1/whoami")
    fun whoami(@AuthenticationPrincipal principal: OperatorPrincipal) =
        OperatorView(principal.operatorId, principal.role.name, principal.purpose)
}
