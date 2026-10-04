package secdrill.controlplane.identity

import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import secdrill.controlplane.identity.web.AuthCookies
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** dev-login works in the local profile and still enforces the Origin rule. */
@IntegrationTest
@ActiveProfiles("local")
class DevLoginTest {
    @LocalServerPort private var port: Int = 0

    @Test
    fun `local dev-login issues a platform login`() {
        val browser = TestBrowser("http://127.0.0.1:$port")
        assertEquals(403, browser.send("POST", "/v1/auth/dev-login", body = """{"subject":"alice"}""").status)
        val login = browser.send("POST", "/v1/auth/dev-login", body = """{"subject":"alice"}""", origin = TEST_ORIGIN)
        assertEquals(204, login.status, login.body)
        assertTrue(AuthCookies.ACCESS in login.setCookies)
        assertEquals(200, browser.send("GET", "/v1/auth/session").status)
    }
}

/** Without the local profile the endpoint does not exist, so the request is simply unauthenticated. */
@IntegrationTest
class DevLoginDisabledTest {
    @LocalServerPort private var port: Int = 0

    @Test
    fun `dev-login is not mounted by default`() {
        val response = TestBrowser("http://127.0.0.1:$port").send("POST", "/v1/auth/dev-login", body = "{}", origin = TEST_ORIGIN)
        assertTrue(response.status >= 400, "status ${response.status}")
        assertTrue(AuthCookies.ACCESS !in response.setCookies)
    }
}
