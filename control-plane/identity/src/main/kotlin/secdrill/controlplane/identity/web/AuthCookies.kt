package secdrill.controlplane.identity.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import secdrill.controlplane.identity.IssuedLogin
import java.time.Clock
import java.time.Duration

/** Cookie names and attributes from 15 and the OpenAPI security schemes. All cookies are Secure. */
object AuthCookies {
    const val ACCESS = "access_session"
    const val REFRESH = "refresh_session"
    const val CSRF = "csrf_token"
    const val CSRF_HEADER = "X-CSRF-Token"
    const val REFRESH_PATH = "/v1/auth"

    fun read(request: HttpServletRequest, name: String): String? =
        request.cookies?.firstOrNull { it.name == name }?.value?.takeIf { it.isNotEmpty() }

    fun write(response: HttpServletResponse, login: IssuedLogin, clock: Clock) {
        val now = clock.instant()
        add(response, cookie(ACCESS, login.accessToken, Duration.between(now, login.accessExpiresAt), "/", "Lax", httpOnly = true))
        val refreshTtl = Duration.between(now, login.refreshExpiresAt)
        add(response, cookie(REFRESH, login.refreshToken, refreshTtl, REFRESH_PATH, "Strict", httpOnly = true))
        // The CSRF token is readable by same-origin script so it can echo it in X-CSRF-Token. It never changes on refresh.
        login.csrfToken?.let { add(response, cookie(CSRF, it, refreshTtl, "/", "Strict", httpOnly = false)) }
    }

    fun clear(response: HttpServletResponse) {
        add(response, cookie(ACCESS, "", Duration.ZERO, "/", "Lax", httpOnly = true))
        add(response, cookie(REFRESH, "", Duration.ZERO, REFRESH_PATH, "Strict", httpOnly = true))
        add(response, cookie(CSRF, "", Duration.ZERO, "/", "Strict", httpOnly = false))
    }

    private fun cookie(name: String, value: String, maxAge: Duration, path: String, sameSite: String, httpOnly: Boolean) =
        ResponseCookie.from(name, value).httpOnly(httpOnly).secure(true).sameSite(sameSite).path(path).maxAge(maxAge).build()

    private fun add(response: HttpServletResponse, cookie: ResponseCookie) =
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
}
