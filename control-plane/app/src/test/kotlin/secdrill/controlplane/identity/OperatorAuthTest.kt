package secdrill.controlplane.identity

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import secdrill.controlplane.identity.web.AuthCookies
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TestBrowser
import secdrill.kernel.OperatorRole
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Operator authentication is separate from learner authentication and audited (15, 19). */
@IntegrationTest
class OperatorAuthTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var sessions: AuthSessionService
    @Autowired private lateinit var identities: UserIdentityService
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private val browser by lazy { TestBrowser("http://127.0.0.1:$port") }

    private fun auditCount(operator: UUID) =
        jdbc.sql("SELECT count(*) FROM audit_events WHERE actor_id = ?").param(operator).query(Int::class.java).single()

    @Test
    fun `operator bearer reaches ops routes and every request is audited`() {
        val operator = UUID.randomUUID()
        val token = operators.issue(operator, OperatorRole.OPERATOR, "drain grading queue", Duration.ofHours(1))
        val response = browser.send("GET", "/ops/v1/whoami", bearer = token, cookies = emptyMap())
        assertEquals(200, response.status, response.body)
        assertEquals("drain grading queue", json.readTree(response.body)["purpose"].asString())
        assertEquals(1, auditCount(operator))
        val action = jdbc.sql("SELECT action FROM audit_events WHERE actor_id = ?").param(operator).query(String::class.java).single()
        assertEquals("GET /ops/v1/whoami", action)
    }

    @Test
    fun `learner cookies are not accepted on ops routes`() {
        val login = sessions.start(identities.findOrCreate("https://idp.test", "user-${UUID.randomUUID()}"))
        val response = browser.send("GET", "/ops/v1/whoami", cookies = mapOf(AuthCookies.ACCESS to login.accessToken))
        assertEquals(401, response.status)
        assertEquals("AUTHENTICATION_REQUIRED", json.readTree(response.body)["code"].asString())
    }

    @Test
    fun `expired, revoked and unknown operator tokens are rejected without audit`() {
        val operator = UUID.randomUUID()
        val expiring = operators.issue(operator, OperatorRole.OPERATOR, "short task", Duration.ofMinutes(5))
        val revoked = operators.issue(operator, OperatorRole.OPERATOR, "revoked task", Duration.ofHours(1))
        operators.revoke(revoked)
        clock.advance(Duration.ofMinutes(6))
        listOf(expiring, revoked, "unknown-token").forEach {
            assertEquals(401, browser.send("GET", "/ops/v1/whoami", bearer = it, cookies = emptyMap()).status)
        }
        assertEquals(0, auditCount(operator))
    }

    @Test
    fun `operator tokens are short-lived and need a purpose`() {
        assertFailsWith<IllegalArgumentException> { operators.issue(UUID.randomUUID(), OperatorRole.OPERATOR, "too long", Duration.ofHours(13)) }
        assertFailsWith<IllegalArgumentException> { operators.issue(UUID.randomUUID(), OperatorRole.OPERATOR, " ", Duration.ofHours(1)) }
    }
}
