package secdrill.gateway

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import secdrill.kernel.ConnectTokens
import secdrill.kernel.Rfc3339
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.PublicKey
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class GatewayConfig(
    val port: Int,
    val controlBaseUrl: String,
    /** GATEWAY workload credential for the internal API. */
    val credential: String,
    val connectKey: PublicKey,
    /** Upstreams the gateway may reach; the Lab's endpoint must match or the request is refused. */
    val allowedUpstream: Regex,
    val sessionTtl: Duration = Duration.ofMinutes(15),
    val maxResponseBytes: Int = 10 * 1024 * 1024,
    val activityInterval: Duration = Duration.ofSeconds(60),
)

/**
 * Lab Gateway (11, 17, ADR 0007): a separate process on its own origin, so platform cookies never reach it.
 * - `/connect?token=` accepts a one-time Ed25519 connect token from Control and sets a gateway-only cookie
 * - every other request re-checks with Control that the Lab is READY for the same generation and owner
 * - it proxies only to the endpoint Control reports for that Lab, and only if that endpoint is allowlisted;
 *   there is no CONNECT, no absolute-form target, no user-chosen destination
 * - the gateway's own `lab_access` cookie and forwarding headers are never passed upstream. The Lab's own
 *   credentials (its cookies, an `Authorization` header for the Lab app) pass through: this origin never receives
 *   platform credentials, because platform cookies are scoped to the platform host and learners have no bearer token.
 */
class LabGateway(private val config: GatewayConfig, private val clock: Clock = Clock.systemUTC()) {
    companion object {
        const val COOKIE = "lab_access"
        private val hopByHop = setOf("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length")
        private val neverForward = setOf("cookie", "forwarded", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-real-ip")
        private val methods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }

    private data class GatewaySession(val labId: UUID, val generation: Int, val ownerId: UUID, val expiresAt: Instant, @Volatile var lastActivity: Instant)

    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val sessions = ConcurrentHashMap<String, GatewaySession>()
    private val usedNonces = ConcurrentHashMap<String, Instant>()
    private val random = SecureRandom()
    private var server: HttpServer? = null

    fun start(): Int {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 0)
        server.executor = Executors.newFixedThreadPool(16)
        server.createContext("/") { exchange -> exchange.use { handle(it) } }
        server.start()
        this.server = server
        return server.address.port
    }

    fun stop() = server?.stop(0)

    private fun handle(exchange: HttpExchange) {
        try {
            val uri = exchange.requestURI
            if (uri.isAbsolute || exchange.requestMethod == "CONNECT") return respond(exchange, 405, "method or target not allowed")
            if (uri.path == "/connect" && exchange.requestMethod == "GET") return connect(exchange)
            proxy(exchange)
        } catch (error: Exception) {
            respond(exchange, 502, "lab unavailable")
        }
    }

    private fun connect(exchange: HttpExchange) {
        val token = exchange.requestURI.rawQuery?.split("&")?.firstOrNull { it.startsWith("token=") }?.removePrefix("token=")
            ?: return respond(exchange, 401, "connect token required")
        val now = clock.instant()
        val claims = ConnectTokens.verify(token, config.connectKey, now) ?: return respond(exchange, 401, "invalid or expired connect token")
        usedNonces.entries.removeIf { it.value.isBefore(now) }
        if (usedNonces.putIfAbsent(claims.nonce, claims.expiresAt) != null) return respond(exchange, 401, "connect token already used")
        val lab = labView(claims.labId) ?: return respond(exchange, 403, "lab not available")
        if (!sameLab(lab, claims.generation, claims.ownerId)) return respond(exchange, 403, "lab not available")
        val labExpiry = Rfc3339.parse(lab["expiresAt"].asString())
        val expires = minOf(now.plus(config.sessionTtl), labExpiry)
        val id = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        sessions[id] = GatewaySession(claims.labId, claims.generation, claims.ownerId, expires, now)
        val maxAge = Duration.between(now, expires).seconds.coerceAtLeast(0)
        exchange.responseHeaders.add("Set-Cookie", "$COOKIE=$id; Path=/; Max-Age=$maxAge; HttpOnly; Secure; SameSite=Strict")
        exchange.responseHeaders.add("Location", "/")
        exchange.sendResponseHeaders(303, -1)
    }

    private fun proxy(exchange: HttpExchange) {
        if (exchange.requestMethod !in methods) return respond(exchange, 405, "method not allowed")
        val id = cookie(exchange, COOKIE) ?: return respond(exchange, 401, "connect to the lab first")
        val session = sessions[id] ?: return respond(exchange, 401, "connect to the lab first")
        val now = clock.instant()
        if (!session.expiresAt.isAfter(now)) return revoke(id, exchange)
        val lab = labView(session.labId)
        if (lab == null || !sameLab(lab, session.generation, session.ownerId)) return revoke(id, exchange)
        val endpoint = lab["endpoint"]?.takeIf { it.isString }?.asString() ?: return revoke(id, exchange)
        if (!config.allowedUpstream.matches(endpoint)) return respond(exchange, 403, "upstream not allowed")

        val target = URI.create(endpoint.trimEnd('/') + exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: ""))
        val body = exchange.requestBody.readNBytes(config.maxResponseBytes)
        val request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
            .method(exchange.requestMethod, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        exchange.requestHeaders.forEach { (name, values) ->
            val lower = name.lowercase()
            if (lower !in hopByHop && lower !in neverForward) values.forEach { runCatching { request.header(name, it) } }
        }
        labCookies(exchange)?.let { request.header("Cookie", it) }
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
        val bytes = response.body().use { it.readNBytes(config.maxResponseBytes + 1) }
        if (bytes.size > config.maxResponseBytes) return respond(exchange, 502, "lab response too large")
        response.headers().map().forEach { (name, values) ->
            val lower = name.lowercase()
            if (lower in hopByHop || lower == ":status") return@forEach
            // The Lab may set its own cookies on this origin, but never the gateway's access cookie.
            values.filterNot { lower == "set-cookie" && it.trimStart().startsWith("$COOKIE=") }.forEach { exchange.responseHeaders.add(name, it) }
        }
        exchange.sendResponseHeaders(response.statusCode(), if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
        if (Duration.between(session.lastActivity, now) >= config.activityInterval) {
            session.lastActivity = now
            runCatching { control("POST", "/internal/v1/gateway/labs/${session.labId}/activity") }
        }
    }

    private fun sameLab(lab: JsonNode, generation: Int, owner: UUID) =
        lab["ready"]?.asBoolean() == true && lab["generation"]?.asInt() == generation && lab["ownerId"]?.asString() == owner.toString()

    private fun revoke(id: String, exchange: HttpExchange) {
        sessions.remove(id)
        exchange.responseHeaders.add("Set-Cookie", "$COOKIE=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict")
        respond(exchange, 403, "lab not available")
    }

    private fun labView(labId: UUID): JsonNode? {
        val response = control("GET", "/internal/v1/gateway/labs/$labId")
        return if (response.statusCode() == 200) json.readTree(response.body()) else null
    }

    private fun control(method: String, path: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(config.controlBaseUrl.trimEnd('/') + path)).header("Authorization", "Bearer ${config.credential}")
            .timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** The request's cookies without the gateway's own access cookie, or null if none remain. */
    private fun labCookies(exchange: HttpExchange): String? = exchange.requestHeaders["Cookie"].orEmpty()
        .flatMap { it.split(";") }.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("$COOKIE=") }
        .takeIf { it.isNotEmpty() }?.joinToString("; ")

    private fun cookie(exchange: HttpExchange, name: String): String? = exchange.requestHeaders["Cookie"].orEmpty()
        .flatMap { it.split(";") }.map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.takeIf { it.isNotEmpty() }

    private fun respond(exchange: HttpExchange, status: Int, message: String) {
        val bytes = json.writeValueAsBytes(mapOf("message" to message))
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
