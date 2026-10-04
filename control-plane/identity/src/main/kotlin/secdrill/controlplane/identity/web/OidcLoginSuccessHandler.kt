package secdrill.controlplane.identity.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Component
import secdrill.controlplane.identity.AuthProperties
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.UserIdentityService
import java.time.Clock

/**
 * After Spring validated the ID token (issuer, audience, signature, nonce), converts the OIDC identity into a
 * platform login. Only `iss` and `sub` are used; IdP tokens and the handshake session are discarded.
 */
@Component
class OidcLoginSuccessHandler(
    private val identities: UserIdentityService,
    private val sessions: AuthSessionService,
    private val properties: AuthProperties,
    private val clock: Clock,
) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        val oidc = authentication.principal as OidcUser
        val userId = identities.findOrCreate(oidc.idToken.issuer.toString(), requireNotNull(oidc.idToken.subject) { "ID token without sub" })
        val login = sessions.start(userId)
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        AuthCookies.write(response, login, clock)
        response.sendRedirect(properties.postLoginPath)
    }
}
