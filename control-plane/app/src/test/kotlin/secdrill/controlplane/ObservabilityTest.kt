package secdrill.controlplane

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Operations telemetry (24): correlation id on every response and low-cardinality operational gauges. */
@IntegrationTest
class ObservabilityTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var registry: MeterRegistry

    private lateinit var browser: TestBrowser

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
    }

    private fun gauge(name: String): Double = registry.get(name).gauge().value()

    @Test
    fun `every response carries a correlation id, even when security rejects it`() {
        val ok = browser.send("GET", "/actuator/health")
        assertEquals(200, ok.status)
        assertNotNull(ok.headers.firstValue("X-Request-Id").orElse(null), "health carries a request id")

        // Unauthenticated learner route: rejected, but the id is still set because the filter runs before security.
        val rejected = browser.send("GET", "/v1/sessions/${UUID.randomUUID()}")
        assertTrue(rejected.status in setOf(401, 403), "unauthenticated is rejected: ${rejected.status}")
        assertNotNull(rejected.headers.firstValue("X-Request-Id").orElse(null), "a rejected request still carries a request id")
    }

    @Test
    fun `an incoming correlation id is echoed only when it is a safe token`() {
        val safe = browser.send("GET", "/actuator/health", headers = mapOf("X-Request-Id" to "trace-abc-123"))
        assertEquals("trace-abc-123", safe.headers.firstValue("X-Request-Id").orElse(null))

        val unsafe = browser.send("GET", "/actuator/health", headers = mapOf("X-Request-Id" to "bad id with spaces"))
        assertTrue(unsafe.headers.firstValue("X-Request-Id").orElse("") != "bad id with spaces", "an unsafe id is replaced")
    }

    @Test
    fun `operational gauges read from the control tables`() {
        // Present and non-negative even on an empty system.
        assertTrue(gauge("secdrill.outbox.backlog") >= 0.0)
        assertTrue(gauge("secdrill.outbox.oldest.age.seconds") >= 0.0)
        assertTrue(gauge("secdrill.labs.active") >= 0.0)
        assertTrue(gauge("secdrill.grading.system.errors") >= 0.0)

        // A quarantined runner shows up in its gauge.
        val before = gauge("secdrill.runners.quarantined")
        val runner = "obs-${UUID.randomUUID()}"
        jdbc.sql("INSERT INTO runner_quarantine(runner_id, reason, quarantined_at) VALUES (?, 'synthetic', ?)")
            .params(runner, OffsetDateTime.now(ZoneOffset.UTC)).update()
        assertEquals(before + 1.0, gauge("secdrill.runners.quarantined"))
        jdbc.sql("DELETE FROM runner_quarantine WHERE runner_id = ?").param(runner).update()
    }
}
