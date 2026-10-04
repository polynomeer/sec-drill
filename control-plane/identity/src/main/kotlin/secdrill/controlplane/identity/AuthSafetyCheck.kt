package secdrill.controlplane.identity

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Component

/**
 * Fails startup on unsafe authentication settings instead of running with them (prompt 03, 15).
 * - dev-login only with the `local` profile and never together with `prod`
 * - `prod` requires an OIDC registration and HTTPS-only allowed origins
 */
@Component
class AuthSafetyCheck(
    private val environment: Environment,
    private val properties: AuthProperties,
    private val registrations: ObjectProvider<ClientRegistrationRepository>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val profiles = environment.activeProfiles.toSet()
        val problems = buildList {
            if (properties.devLogin.enabled && ("local" !in profiles || "prod" in profiles)) {
                add("secdrill.auth.dev-login.enabled requires the 'local' profile and must not run with 'prod'")
            }
            if ("prod" in profiles) {
                if (registrations.ifAvailable == null) add("prod requires an OIDC client registration")
                if (properties.allowedOrigins.isEmpty() || properties.allowedOrigins.any { !it.startsWith("https://") }) {
                    add("prod requires secdrill.auth.allowed-origins with https origins only")
                }
            }
        }
        check(problems.isEmpty()) { "Unsafe authentication configuration: " + problems.joinToString("; ") }
    }
}
