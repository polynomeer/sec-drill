package secdrill.controlplane.web

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.IssuedLogin
import secdrill.controlplane.identity.UserIdentityService
import secdrill.controlplane.identity.web.AuthCookies
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.kernel.Mode
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HTTP errors use the contract envelope and never leak internals (15). */
@IntegrationTest
@Import(ErrorEnvelopeTest.ProbeConfig::class)
class ErrorEnvelopeTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var sessions: AuthSessionService
    @Autowired private lateinit var identities: UserIdentityService

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private lateinit var login: IssuedLogin

    data class Probe(val mode: Mode, val at: Instant)

    @TestConfiguration(proxyBeanMethods = false)
    @Import(ProbeController::class)
    class ProbeConfig

    @RestController
    class ProbeController {
        @PostMapping("/v1/test-probe/echo")
        fun echo(@RequestBody probe: Probe) = probe

        @GetMapping("/v1/test-probe/fail")
        fun fail(): Nothing = throw IllegalStateException("secret-detail-from-internal-state")
    }

    @BeforeTest
    fun login() {
        browser = TestBrowser("http://127.0.0.1:$port")
        login = sessions.start(identities.findOrCreate("https://idp.test", "user-${UUID.randomUUID()}"))
        browser.jar[AuthCookies.ACCESS] = login.accessToken
    }

    private fun post(path: String, body: String) = browser.send("POST", path, body = body, origin = TEST_ORIGIN, csrf = login.csrfToken)

    private fun assertEnvelope(response: TestResponse, expectedStatus: Int, expectedCode: String, retryable: Boolean): JsonNode {
        assertEquals(expectedStatus, response.status, response.body)
        val node = json.readTree(response.body)
        assertTrue(setOf("code", "message", "requestId", "retryable", "details").containsAll(node.propertyNames().toSet()), response.body)
        assertEquals(expectedCode, node["code"].asString())
        assertEquals(retryable, node["retryable"].asBoolean())
        Uuids.parse(node["requestId"].asString())
        return node
    }

    @Test
    fun `valid input round-trips enums and RFC 3339 UTC timestamps`() {
        val response = post("/v1/test-probe/echo", """{"mode":"PURPLE","at":"2026-10-04T09:00:00+09:00"}""")
        assertEquals(200, response.status, response.body)
        assertEquals("""{"mode":"PURPLE","at":"2026-10-04T00:00:00Z"}""", response.body)
    }

    @Test
    fun `malformed JSON is MALFORMED_REQUEST without details`() {
        val node = assertEnvelope(post("/v1/test-probe/echo", """{"mode":"""), 400, "MALFORMED_REQUEST", retryable = false)
        assertFalse(node.has("details"))
    }

    @Test
    fun `unknown enum value is rejected`() =
        assertEnvelope(post("/v1/test-probe/echo", """{"mode":"SPEEDRUN","at":"2026-10-04T00:00:00Z"}"""), 400, "MALFORMED_REQUEST", false).let { }

    @Test
    fun `unknown path is NOT_FOUND for a logged-in user and AUTHENTICATION_REQUIRED otherwise`() {
        assertEnvelope(browser.send("GET", "/v1/does-not-exist"), 404, "NOT_FOUND", retryable = false)
        assertEnvelope(browser.send("GET", "/v1/does-not-exist", cookies = emptyMap()), 401, "AUTHENTICATION_REQUIRED", retryable = false)
    }

    @Test
    fun `unsupported method is a client error, not an internal error`() =
        assertEnvelope(browser.send("DELETE", "/v1/test-probe/echo", origin = TEST_ORIGIN, csrf = login.csrfToken), 400, "MALFORMED_REQUEST", false).let { }

    @Test
    fun `unexpected exception is INTERNAL_ERROR and hides the cause`() {
        val response = browser.send("GET", "/v1/test-probe/fail")
        assertEnvelope(response, 500, "INTERNAL_ERROR", retryable = false)
        assertFalse(response.body.contains("secret-detail"), response.body)
        assertFalse(response.body.contains("IllegalStateException"), response.body)
    }
}
