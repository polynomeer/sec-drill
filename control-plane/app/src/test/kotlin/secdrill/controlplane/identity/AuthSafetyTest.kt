package secdrill.controlplane.identity

import org.springframework.boot.builder.SpringApplicationBuilder
import secdrill.controlplane.ControlPlaneApplication
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Unsafe authentication settings must stop startup (prompt 03). No database is touched before the check fails. */
class AuthSafetyTest {
    private fun assertStartupFails(profiles: Array<String>, vararg properties: String, expected: String) {
        // Command-line arguments outrank application.yaml; builder default properties would not.
        val arguments = (listOf("server.port=0", "spring.flyway.enabled=false") + properties).map { "--$it" }.toTypedArray()
        val builder = SpringApplicationBuilder(ControlPlaneApplication::class.java).profiles(*profiles)
        try {
            builder.run(*arguments).close()
            fail("startup should have failed for profiles=${profiles.toList()} ${properties.toList()}")
        } catch (error: Exception) {
            val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue(expected in messages, messages)
        }
    }

    @Test
    fun `dev-login outside the local profile fails startup`() =
        assertStartupFails(emptyArray(), "secdrill.auth.dev-login.enabled=true", expected = "dev-login.enabled requires the 'local' profile")

    @Test
    fun `dev-login together with prod fails startup`() =
        assertStartupFails(arrayOf("local", "prod"), "secdrill.auth.dev-login.enabled=true", expected = "must not run with 'prod'")

    @Test
    fun `prod without OIDC and https origins fails startup`() =
        assertStartupFails(arrayOf("prod"), "secdrill.auth.allowed-origins=http://insecure.test", expected = "prod requires an OIDC client registration")
}
