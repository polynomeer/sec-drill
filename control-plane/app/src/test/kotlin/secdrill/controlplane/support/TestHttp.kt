package secdrill.controlplane.support

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A parsed Set-Cookie header, keeping attributes so tests can assert them. */
data class SetCookie(val name: String, val value: String, val attributes: Map<String, String>) {
    fun has(attribute: String) = attributes.keys.any { it.equals(attribute, ignoreCase = true) }
    fun attr(attribute: String) = attributes.entries.firstOrNull { it.key.equals(attribute, ignoreCase = true) }?.value

    companion object {
        fun parse(header: String): SetCookie {
            val parts = header.split(";").map { it.trim() }
            val (name, value) = parts.first().split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val attributes = parts.drop(1).associate { part -> part.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } } }
            return SetCookie(name, value, attributes)
        }
    }
}

data class TestResponse(val status: Int, val body: String, val headers: java.net.http.HttpHeaders) {
    val setCookies: Map<String, SetCookie> get() = headers.allValues("Set-Cookie").map(SetCookie::parse).associateBy { it.name }
    fun location(): String? = headers.firstValue("Location").orElse(null)
}

/**
 * Minimal browser: no automatic redirects, explicit cookie jar. java.net.CookieManager drops Secure cookies over
 * plain HTTP, so the jar is managed here and cookie attributes stay visible to assertions.
 */
class TestBrowser(private val base: String) {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val jar = mutableMapOf<String, String>()

    fun send(
        method: String,
        url: String,
        body: String? = null,
        origin: String? = null,
        csrf: String? = null,
        bearer: String? = null,
        cookies: Map<String, String> = jar,
        headers: Map<String, String> = emptyMap(),
    ): TestResponse {
        val target = if (url.startsWith("http")) url else base + url
        val builder = HttpRequest.newBuilder(URI.create(target))
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) builder.header("Content-Type", "application/json")
        origin?.let { builder.header("Origin", it) }
        csrf?.let { builder.header("X-CSRF-Token", it) }
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (cookies.isNotEmpty() && target.startsWith(base)) builder.header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val result = TestResponse(response.statusCode(), response.body(), response.headers())
        if (target.startsWith(base)) result.setCookies.values.forEach { if (it.attr("Max-Age") == "0") jar.remove(it.name) else jar[it.name] = it.value }
        return result
    }
}
