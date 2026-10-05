package secdrill.controlplane.lab

import com.sun.net.httpserver.HttpServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.identity.WorkloadCredentialService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TestBrowser
import secdrill.execution.agent.HttpLabControl
import secdrill.execution.agent.RunnerAgent
import secdrill.gateway.GatewayConfig
import secdrill.gateway.LabGateway
import secdrill.kernel.ConnectTokens
import secdrill.kernel.WorkloadKind
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lab Gateway against the real Control internal API (11, 17, ADR 0007). The upstream is a local synthetic HTTP
 * server standing in for a Lab app; the runner-to-Lab network path is not exercised here.
 */
@IntegrationTest
@TestPropertySource(properties = ["test.context=lab-gateway", "secdrill.lab.gateway-base-url=http://gateway.invalid"])
class LabGatewayTest {
    companion object {
        private val keys = ConnectTokens.generate()

        @JvmStatic
        @DynamicPropertySource
        fun signing(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.lab.connect-signing-key") { keys.first }
        }
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var workloads: WorkloadCredentialService
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val upstreamRequests = ConcurrentLinkedQueue<Map<String, List<String>>>()
    private lateinit var upstream: HttpServer
    private lateinit var gateway: LabGateway
    private lateinit var gatewayBase: String
    private lateinit var browser: TestBrowser

    private val upstreamBase get() = "http://127.0.0.1:${upstream.address.port}"

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE kind IN ('PROVISION','CLEANUP') AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").update()
        jdbc.sql(
            """UPDATE labs SET state = 'TERMINATED', desired_state = 'TERMINATED', terminate_reason = coalesce(terminate_reason, 'OPERATOR'),
               terminate_requested_at = coalesce(terminate_requested_at, now()), cleanup_confirmed_at = now(), cleanup_receipt = '{}' WHERE cleanup_confirmed_at IS NULL""",
        ).update()
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    upstreamRequests.add(it.requestHeaders.mapKeys { (name, _) -> name.lowercase() })
                    val body = "synthetic-lab ${it.requestURI}".toByteArray()
                    it.responseHeaders.add("Set-Cookie", "${LabGateway.COOKIE}=hijack; Path=/")
                    it.responseHeaders.add("Set-Cookie", "app_session=synthetic; Path=/")
                    it.sendResponseHeaders(200, body.size.toLong())
                    it.responseBody.write(body)
                }
            }
            start()
        }
        gateway = LabGateway(
            GatewayConfig(
                port = 0,
                controlBaseUrl = "http://127.0.0.1:$port",
                credential = workloads.issue("gateway-test", WorkloadKind.GATEWAY, Duration.ofHours(1)),
                connectKey = ConnectTokens.publicKey(keys.second),
                allowedUpstream = Regex("^http://127\\.0\\.0\\.1:\\d+$"),
            ),
            clock,
        )
        gatewayBase = "http://127.0.0.1:${gateway.start()}"
    }

    @AfterTest
    fun tearDown() {
        gateway.stop()
        upstream.stop(0)
    }

    private data class Ready(val learner: Fixtures.Learner, val session: UUID, val lab: UUID)

    private fun readyLab(endpoint: String = upstreamBase): Ready {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val response = LabCalls.requestLab(browser, learner, session)
        assertEquals(202, response.status, response.body)
        val lab = UUID.fromString(json.readTree(response.body)["id"].asString())
        val control = HttpLabControl("http://127.0.0.1:$port", workloads.issue("runner-gateway", WorkloadKind.AGENT, Duration.ofHours(1)))
        val agent = RunnerAgent(control, FakeRuntime(endpoint))
        generateSequence { agent.runOnce() }.first { it.lab.labId == lab }
        assertEquals("READY", fixtures.string("SELECT state FROM labs WHERE id = ?", lab))
        return Ready(learner, session, lab)
    }

    private fun connectUrl(ready: Ready): String {
        val response = LabCalls.connect(browser, ready.learner, ready.session, ready.lab)
        assertEquals(200, response.status, response.body)
        val view = json.readTree(response.body)
        val schema = json.readTree(Path.of(System.getProperty("secdrill.contracts.dir"), "openapi.yaml").toFile())["components"]["schemas"]["LabConnect"]
        assertEquals(schema["properties"].propertyNames().toSet(), view.propertyNames().toSet())
        val url = view["connectUrl"].asString()
        assertTrue(url.startsWith("http://gateway.invalid/connect?token="), url)
        return url.replace("http://gateway.invalid", gatewayBase)
    }

    private fun get(url: String, cookie: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).GET()
        cookie?.let { builder.header("Cookie", it) }
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun accessCookie(response: HttpResponse<String>): String {
        val header = response.headers().allValues("Set-Cookie").single { it.startsWith("${LabGateway.COOKIE}=") }
        return header.substringBefore(";")
    }

    @Test
    fun `a connect token is accepted once and sets a gateway-only strict cookie`() {
        val ready = readyLab()
        val url = connectUrl(ready)
        val first = get(url)
        assertEquals(303, first.statusCode(), first.body())
        assertEquals("/", first.headers().firstValue("Location").orElse(null))
        val cookie = first.headers().allValues("Set-Cookie").single { it.startsWith("${LabGateway.COOKIE}=") }
        listOf("HttpOnly", "Secure", "SameSite=Strict", "Path=/").forEach { assertTrue(it in cookie, "$it missing in $cookie") }

        assertEquals(401, get(url).statusCode(), "a connect token is single use")
        val token = url.substringAfter("token=")
        val tampered = token.substringBefore(".") + "." + token.substringAfter(".").reversed()
        assertEquals(401, get("$gatewayBase/connect?token=$tampered").statusCode(), "a forged signature is refused")
        assertEquals(401, get("$gatewayBase/connect").statusCode(), "no token, no session")
        assertEquals(401, get("$gatewayBase/").statusCode(), "proxying needs a gateway session")

        val foreign = ConnectTokens.generate()
        val forgedToken = ConnectTokens.sign(
            ConnectTokens.Claims(ready.lab, 1, ready.learner.userId.value, clock.instant().plusSeconds(60), "forged-nonce"),
            ConnectTokens.privateKey(foreign.first),
        )
        assertEquals(401, get("$gatewayBase/connect?token=$forgedToken").statusCode(), "a token signed by another key is refused")
    }

    @Test
    fun `the gateway credential never reaches the Lab, the Lab's own credentials do, and the Lab cannot set the access cookie`() {
        val ready = readyLab()
        val cookie = accessCookie(get(connectUrl(ready)))
        upstreamRequests.clear()
        val response = get(
            "$gatewayBase/notes?x=1",
            cookie = "$cookie; app_session=from-browser",
            headers = mapOf("Authorization" to "Bearer synthetic-not-a-secret", "X-Forwarded-For" to "203.0.113.9", "X-Lab-Probe" to "kept"),
        )
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("synthetic-lab /notes?x=1", response.body())
        val seen = upstreamRequests.single()
        assertEquals(listOf("app_session=from-browser"), seen["cookie"], "only the Lab's own cookies reach the Lab: $seen")
        assertEquals(listOf("Bearer synthetic-not-a-secret"), seen["authorization"], "the Lab app's own login header passes through")
        assertFalse("x-forwarded-for" in seen)
        assertEquals(listOf("kept"), seen["x-lab-probe"])
        val setCookies = response.headers().allValues("Set-Cookie")
        assertTrue(setCookies.none { it.startsWith("${LabGateway.COOKIE}=") }, "the Lab must not overwrite the gateway cookie: $setCookies")
        assertTrue(setCookies.any { it.startsWith("app_session=synthetic") }, "the Lab's own cookies pass through")
    }

    @Test
    fun `CONNECT and absolute-form targets are refused`() {
        val ready = readyLab()
        val cookie = accessCookie(get(connectUrl(ready)))
        val gatewayPort = URI.create(gatewayBase).port
        fun raw(requestLine: String): String = Socket("127.0.0.1", gatewayPort).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write("$requestLine\r\nHost: 127.0.0.1\r\nCookie: $cookie\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine()
        }
        // The JDK server rejects authority-form CONNECT (400) before the handler; either way no tunnel is opened.
        raw("CONNECT 1.1.1.1:443 HTTP/1.1").let { assertTrue(it.contains(" 400") || it.contains(" 405"), "CONNECT tunnels are not offered: $it") }
        assertTrue(raw("GET http://169.254.169.254/latest/meta-data/ HTTP/1.1").contains(" 405"), "no user-chosen destinations")
        assertTrue(upstreamRequests.isEmpty())
    }

    @Test
    fun `access ends when the Lab stops and non-allowlisted endpoints are never proxied`() {
        val ready = readyLab()
        val cookie = accessCookie(get(connectUrl(ready)))
        assertEquals(200, get("$gatewayBase/", cookie).statusCode())
        val version = fixtures.count("SELECT version FROM sessions WHERE id = ?", ready.session).toLong()
        assertEquals(202, LabCalls.stop(browser, ready.learner, ready.session, version).status)
        val after = get("$gatewayBase/", cookie)
        assertEquals(403, after.statusCode(), "a stopped Lab is not reachable through an old gateway session")
        assertNotNull(after.headers().allValues("Set-Cookie").firstOrNull { it.startsWith("${LabGateway.COOKIE}=;") && "Max-Age=0" in it }, "the cookie is cleared")
        assertEquals(401, get("$gatewayBase/", cookie).statusCode(), "the gateway session is gone")

        val metadata = readyLab(endpoint = "http://169.254.169.254:80")
        val metadataCookie = accessCookie(get(connectUrl(metadata)))
        upstreamRequests.clear()
        assertEquals(403, get("$gatewayBase/latest/meta-data/", metadataCookie).statusCode(), "endpoints outside the allowlist are refused")
    }
}
