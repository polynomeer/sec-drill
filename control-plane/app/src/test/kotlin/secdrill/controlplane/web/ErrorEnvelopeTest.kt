package secdrill.controlplane.web

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.ControlPlaneApplication
import secdrill.kernel.Mode
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HTTP errors use the contract envelope and never leak internals (15). No database is needed. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ControlPlaneApplication::class, ErrorEnvelopeTest.ProbeController::class],
)
class ErrorEnvelopeTest {
    @LocalServerPort
    private var port: Int = 0

    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()

    data class Probe(val mode: Mode, val at: Instant)

    @RestController
    class ProbeController {
        @PostMapping("/test-probe/echo")
        fun echo(@RequestBody probe: Probe) = probe

        @GetMapping("/test-probe/fail")
        fun fail(): Nothing = throw IllegalStateException("secret-detail-from-internal-state")
    }

    private fun send(method: String, path: String, body: String? = null): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return response.statusCode() to response.body()
    }

    private fun assertEnvelope(status: Int, body: String, expectedStatus: Int, expectedCode: String, retryable: Boolean): JsonNode {
        assertEquals(expectedStatus, status, body)
        val node = json.readTree(body)
        assertTrue(setOf("code", "message", "requestId", "retryable", "details").containsAll(node.propertyNames().toSet()), body)
        assertEquals(expectedCode, node["code"].asString())
        assertEquals(retryable, node["retryable"].asBoolean())
        Uuids.parse(node["requestId"].asString())
        return node
    }

    @Test
    fun `valid input round-trips enums and RFC 3339 UTC timestamps`() {
        val (status, body) = send("POST", "/test-probe/echo", """{"mode":"PURPLE","at":"2026-10-04T09:00:00+09:00"}""")
        assertEquals(200, status, body)
        assertEquals("""{"mode":"PURPLE","at":"2026-10-04T00:00:00Z"}""", body)
    }

    @Test
    fun `malformed JSON is MALFORMED_REQUEST without details`() {
        val (status, body) = send("POST", "/test-probe/echo", """{"mode":""")
        val node = assertEnvelope(status, body, 400, "MALFORMED_REQUEST", retryable = false)
        assertFalse(node.has("details"))
    }

    @Test
    fun `unknown enum value is rejected`() {
        val (status, body) = send("POST", "/test-probe/echo", """{"mode":"SPEEDRUN","at":"2026-10-04T00:00:00Z"}""")
        assertEnvelope(status, body, 400, "MALFORMED_REQUEST", retryable = false)
    }

    @Test
    fun `unknown path is NOT_FOUND`() {
        val (status, body) = send("GET", "/v1/does-not-exist")
        assertEnvelope(status, body, 404, "NOT_FOUND", retryable = false)
    }

    @Test
    fun `unsupported method is a client error, not an internal error`() {
        val (status, body) = send("DELETE", "/test-probe/echo")
        assertEnvelope(status, body, 400, "MALFORMED_REQUEST", retryable = false)
    }

    @Test
    fun `unexpected exception is INTERNAL_ERROR and hides the cause`() {
        val (status, body) = send("GET", "/test-probe/fail")
        assertEnvelope(status, body, 500, "INTERNAL_ERROR", retryable = false)
        assertFalse(body.contains("secret-detail"), body)
        assertFalse(body.contains("IllegalStateException"), body)
    }
}
