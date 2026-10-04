package secdrill.controlplane.identity

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Login session settings (15). Defaults are the MVP assumptions from 00/15. */
@ConfigurationProperties("secdrill.auth")
data class AuthProperties(
    val accessTtl: Duration = Duration.ofMinutes(15),
    val refreshTtl: Duration = Duration.ofDays(7),
    /** Exact `Origin` values allowed for unsafe methods. Empty means every unsafe request is rejected. */
    val allowedOrigins: List<String> = emptyList(),
    /** Where the browser lands after a successful OIDC login. */
    val postLoginPath: String = "/",
    val devLogin: DevLogin = DevLogin(),
) {
    /** Local-only login without an identity provider. Startup fails if enabled outside the `local` profile. */
    data class DevLogin(val enabled: Boolean = false)
}
