package secdrill.controlplane.identity

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import secdrill.controlplane.identity.web.AuthCookies
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.kernel.OperatorRole
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Login session lifecycle over HTTP (15, FR-01): expiry, revocation, refresh rotation and reuse, CSRF and Origin. */
@IntegrationTest
class AuthSessionFlowTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var sessions: AuthSessionService
    @Autowired private lateinit var identities: UserIdentityService
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private lateinit var login: IssuedLogin

    @BeforeTest
    fun login() {
        browser = TestBrowser("http://127.0.0.1:$port")
        login = sessions.start(identities.findOrCreate("https://idp.test", "user-${UUID.randomUUID()}"))
        browser.jar[AuthCookies.ACCESS] = login.accessToken
        browser.jar[AuthCookies.REFRESH] = login.refreshToken
        browser.jar[AuthCookies.CSRF] = login.csrfToken!!
    }

    private fun me(cookies: Map<String, String> = browser.jar) = browser.send("GET", "/v1/auth/session", cookies = cookies)
    private fun refresh(cookies: Map<String, String> = browser.jar, origin: String? = TEST_ORIGIN) =
        browser.send("POST", "/v1/auth/refresh", origin = origin, cookies = cookies)
    private fun code(response: TestResponse) = json.readTree(response.body)["code"].asString()
    private fun revokeReason() = jdbc.sql("SELECT revoke_reason FROM auth_sessions WHERE id = ?")
        .param(login.authSessionId).query(String::class.java).optional().orElse(null)

    @Test
    fun `valid access cookie authenticates and anonymous requests get 401`() {
        val ok = me()
        assertEquals(200, ok.status, ok.body)
        assertEquals(login.accessExpiresAt.toString(), json.readTree(ok.body)["accessExpiresAt"].asString())
        val anonymous = me(cookies = emptyMap())
        assertEquals(401, anonymous.status)
        assertEquals("AUTHENTICATION_REQUIRED", code(anonymous))
        assertEquals(401, me(cookies = mapOf(AuthCookies.ACCESS to "forged-token")).status)
    }

    @Test
    fun `authenticated requests never create a server session that could outlive the token`() {
        val response = me()
        assertEquals(200, response.status)
        assertTrue(response.headers.allValues("Set-Cookie").none { it.startsWith("JSESSIONID") }, response.headers.map().toString())
        sessions.revoke(login.authSessionId, secdrill.kernel.AuthRevokeReason.OPERATOR)
        assertEquals(401, me().status)
    }

    @Test
    fun `access expires after 15 minutes`() {
        clock.advance(Duration.ofMinutes(14))
        assertEquals(200, me().status)
        clock.advance(Duration.ofMinutes(2))
        assertEquals(401, me().status)
    }

    @Test
    fun `refresh rotates tokens and invalidates the previous access token`() {
        val oldAccess = login.accessToken
        val rotated = refresh()
        assertEquals(204, rotated.status, rotated.body)
        val access = rotated.setCookies.getValue(AuthCookies.ACCESS)
        val refreshCookie = rotated.setCookies.getValue(AuthCookies.REFRESH)
        assertNotEquals(oldAccess, access.value)
        assertNotEquals(login.refreshToken, refreshCookie.value)
        assertEquals(200, me().status)
        assertEquals(401, me(cookies = mapOf(AuthCookies.ACCESS to oldAccess)).status)
    }

    @Test
    fun `reusing a rotated refresh token revokes the whole login`() {
        val stolen = login.refreshToken
        assertEquals(204, refresh().status)
        assertEquals(200, me().status)

        val replay = refresh(cookies = mapOf(AuthCookies.REFRESH to stolen))
        assertEquals(401, replay.status)
        assertEquals("REFRESH_REUSE", revokeReason())
        assertEquals(401, me().status, "access issued by the rotation must die with the login")
        assertEquals(401, refresh().status, "the current refresh token must die with the login")
    }

    @Test
    fun `refresh requires an allowed Origin and an unexpired token`() {
        assertEquals(403, refresh(origin = null).status)
        assertEquals(403, refresh(origin = "https://evil.test").status)
        assertEquals(401, refresh(cookies = emptyMap()).status)
        clock.advance(Duration.ofDays(7).plusSeconds(1))
        assertEquals(401, refresh().status)
    }

    @Test
    fun `logout revokes the login and clears cookies`() {
        val response = browser.send("POST", "/v1/auth/logout", origin = TEST_ORIGIN, csrf = login.csrfToken)
        assertEquals(204, response.status, response.body)
        listOf(AuthCookies.ACCESS, AuthCookies.REFRESH, AuthCookies.CSRF)
            .forEach { assertEquals("0", response.setCookies.getValue(it).attr("Max-Age"), it) }
        assertEquals("LOGOUT", revokeReason())
        val revoked = mapOf(AuthCookies.ACCESS to login.accessToken, AuthCookies.REFRESH to login.refreshToken)
        assertEquals(401, me(cookies = revoked).status)
        assertEquals(401, refresh(cookies = revoked).status)
    }

    @Test
    fun `logout without matching CSRF token or Origin is rejected and leaves the login intact`() {
        val missing = browser.send("POST", "/v1/auth/logout", origin = TEST_ORIGIN)
        val wrong = browser.send("POST", "/v1/auth/logout", origin = TEST_ORIGIN, csrf = "not-the-token")
        val otherLogin = sessions.start(identities.findOrCreate("https://idp.test", "other-${UUID.randomUUID()}"))
        val foreign = browser.send("POST", "/v1/auth/logout", origin = TEST_ORIGIN, csrf = otherLogin.csrfToken)
        val noOrigin = browser.send("POST", "/v1/auth/logout", csrf = login.csrfToken)
        listOf(missing, wrong, foreign, noOrigin).forEach {
            assertEquals(403, it.status, it.body)
            assertEquals("FORBIDDEN", code(it))
        }
        assertEquals(null, revokeReason())
        assertEquals(200, me().status)
    }

    @Test
    fun `revocation takes effect immediately`() {
        sessions.revoke(login.authSessionId, secdrill.kernel.AuthRevokeReason.OPERATOR)
        assertEquals(401, me().status)
    }

    @Test
    fun `operator bearer is not accepted on learner routes`() {
        val token = operators.issue(UUID.randomUUID(), OperatorRole.OPERATOR, "incident review", Duration.ofHours(1))
        val response = browser.send("GET", "/v1/auth/session", bearer = token, cookies = emptyMap())
        assertEquals(401, response.status)
    }

    @Test
    fun `issued cookies are HttpOnly, Secure and scoped`() {
        val rotated = refresh()
        val access = rotated.setCookies.getValue(AuthCookies.ACCESS)
        val refreshCookie = rotated.setCookies.getValue(AuthCookies.REFRESH)
        assertTrue(access.has("HttpOnly") && access.has("Secure"))
        assertEquals("Lax", access.attr("SameSite"))
        assertEquals("/", access.attr("Path"))
        assertEquals("900", access.attr("Max-Age"))
        assertTrue(refreshCookie.has("HttpOnly") && refreshCookie.has("Secure"))
        assertEquals("Strict", refreshCookie.attr("SameSite"))
        assertEquals("/v1/auth", refreshCookie.attr("Path"))
        assertEquals("604800", refreshCookie.attr("Max-Age"))
    }
}
