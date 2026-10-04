package secdrill.controlplane.identity

import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mock.env.MockEnvironment
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import secdrill.controlplane.ControlPlaneApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith
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

    /**
     * Unit-level so other prod checks (e.g. the local artifact store) cannot fail startup first and mask this rule.
     */
    @Test
    fun `prod without OIDC and https origins is refused`() {
        val noRegistrations = StaticListableBeanFactory().getBeanProvider(ClientRegistrationRepository::class.java)
        val check = AuthSafetyCheck(MockEnvironment().apply { setActiveProfiles("prod") }, AuthProperties(allowedOrigins = listOf("http://insecure.test")), noRegistrations)
        val error = assertFailsWith<IllegalStateException> { check.afterSingletonsInstantiated() }
        assertTrue("prod requires an OIDC client registration" in error.message!! && "https origins only" in error.message!!, error.message)
    }
}
