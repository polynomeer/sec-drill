package secdrill.controlplane.identity

import no.nav.security.mock.oauth2.MockOAuth2Server
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import secdrill.controlplane.identity.web.AuthCookies
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Authorization Code + PKCE flow against a local mock identity provider (ADR 0002). */
@IntegrationTest
class OidcLoginTest {
    companion object {
        private val idp = MockOAuth2Server().apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun oidc(registry: DynamicPropertyRegistry) {
            val prefix = "spring.security.oauth2.client"
            registry.add("$prefix.provider.oidc.issuer-uri") { idp.issuerUrl("default").toString() }
            registry.add("$prefix.registration.oidc.client-id") { "secdrill-test" }
            registry.add("$prefix.registration.oidc.client-secret") { "test-client-secret" }
            registry.add("$prefix.registration.oidc.scope") { "openid" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = idp.shutdown()
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcClient
    private val json = JsonMapper.builder().build()

    private fun query(url: String) = URI.create(url).rawQuery.split("&").associate { it.substringBefore("=") to it.substringAfter("=") }

    /** Runs the browser through app -> IdP -> callback and returns the browser and the callback response. */
    private fun loginAs(subject: String, tamperState: Boolean = false): Pair<TestBrowser, secdrill.controlplane.support.TestResponse> {
        idp.enqueueCallback(DefaultOAuth2TokenCallback(issuerId = "default", subject = subject))
        val browser = TestBrowser("http://127.0.0.1:$port")
        val start = browser.send("GET", "/oauth2/authorization/oidc")
        assertEquals(302, start.status, start.body)
        val authorize = assertNotNull(start.location())
        val params = query(authorize)
        assertEquals("S256", params["code_challenge_method"], "PKCE must be used")
        assertNotNull(params["nonce"])
        val idpRedirect = browser.send("GET", authorize)
        assertEquals(302, idpRedirect.status, idpRedirect.body)
        var callback = assertNotNull(idpRedirect.location())
        if (tamperState) callback = callback.replace(Regex("state=[^&]+"), "state=forged")
        return browser to browser.send("GET", callback)
    }

    @Test
    fun `successful login issues platform cookies and maps the subject to one user`() {
        val subject = "learner-${UUID.randomUUID()}"
        val (browser, callback) = loginAs(subject)
        assertEquals(302, callback.status, callback.body)
        assertEquals("/", URI.create(callback.location()!!).path)
        val cookies = callback.setCookies
        listOf(AuthCookies.ACCESS, AuthCookies.REFRESH, AuthCookies.CSRF).forEach { assertTrue(it in cookies, "missing $it") }
        assertTrue(cookies.getValue(AuthCookies.ACCESS).has("HttpOnly"))
        assertTrue(!cookies.getValue(AuthCookies.CSRF).has("HttpOnly"), "CSRF cookie must be readable by same-origin script")
        assertEquals("Strict", cookies.getValue(AuthCookies.CSRF).attr("SameSite"))

        val me = browser.send("GET", "/v1/auth/session")
        assertEquals(200, me.status, me.body)
        val userId = json.readTree(me.body)["userId"].asString()

        val (again, _) = loginAs(subject)
        assertEquals(userId, json.readTree(again.send("GET", "/v1/auth/session").body)["userId"].asString())
        val stored = jdbc.sql("SELECT count(*) FROM user_identities WHERE subject = ?").param(subject).query(Int::class.java).single()
        assertEquals(1, stored)
    }

    @Test
    fun `forged state is rejected without issuing cookies`() {
        val (_, callback) = loginAs("learner-${UUID.randomUUID()}", tamperState = true)
        assertEquals(401, callback.status, callback.body)
        assertTrue(AuthCookies.ACCESS !in callback.setCookies)
    }
}
