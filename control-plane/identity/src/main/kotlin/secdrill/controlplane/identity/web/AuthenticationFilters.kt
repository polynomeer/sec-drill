package secdrill.controlplane.identity.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import secdrill.controlplane.identity.AuthProperties
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.identity.Secrets

const val ROLE_LEARNER = "ROLE_LEARNER"
const val ROLE_OPERATOR = "ROLE_OPERATOR"

private val unsafeMethods = setOf("POST", "PUT", "PATCH", "DELETE")

/** Authenticates learner routes from the `access_session` cookie. Bearer headers are not accepted here. */
class AccessCookieFilter(private val sessions: AuthSessionService) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        sessions.authenticate(AuthCookies.read(request, AuthCookies.ACCESS))?.let { principal ->
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, listOf(SimpleGrantedAuthority(ROLE_LEARNER)))
        }
        chain.doFilter(request, response)
    }
}

/**
 * Unsafe methods require an allowed `Origin`; logged-in requests also require `X-CSRF-Token` matching the login (15).
 * Runs after [AccessCookieFilter]. Refresh and dev-login have no login yet, so only the Origin rule applies to them.
 */
class OriginCsrfFilter(
    private val properties: AuthProperties,
    private val errors: ErrorEnvelopeWriter,
) : OncePerRequestFilter() {
    private val originOnlyPaths = setOf("/v1/auth/refresh", "/v1/auth/dev-login")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.method in unsafeMethods) {
            val origin = request.getHeader(HttpHeaders.ORIGIN)
            if (origin == null || origin !in properties.allowedOrigins) return errors.forbidden(response)
            val principal = SecurityContextHolder.getContext().authentication?.principal as? LearnerPrincipal
            if (principal != null && request.servletPath !in originOnlyPaths &&
                !Secrets.matches(request.getHeader(AuthCookies.CSRF_HEADER), principal.csrfHash)
            ) {
                return errors.forbidden(response)
            }
        }
        chain.doFilter(request, response)
    }
}

/**
 * Authenticates `/ops` routes from `Authorization: Bearer` operator tokens only and audits every authenticated request
 * before it proceeds. Learner cookies are ignored on this chain.
 */
class OperatorBearerFilter(
    private val operators: OperatorAccessService,
    private val errors: ErrorEnvelopeWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val bearer = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val principal = operators.authenticate(bearer)
        if (principal != null) {
            try {
                operators.audit(principal, "${request.method} ${request.requestURI}")
            } catch (error: RuntimeException) {
                logger.error("Operator audit write failed; rejecting request", error)
                return errors.write(response, secdrill.kernel.ErrorCode.SERVICE_UNAVAILABLE, "Audit unavailable")
            }
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, listOf(SimpleGrantedAuthority(ROLE_OPERATOR)))
        }
        chain.doFilter(request, response)
    }
}
