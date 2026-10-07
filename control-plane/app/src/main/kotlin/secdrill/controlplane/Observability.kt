package secdrill.controlplane

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import java.util.function.Supplier

/**
 * Platform operations telemetry (24). Low-cardinality gauges over the control tables; no sessionId label and no
 * learner data. They are registered on the MeterRegistry; the scrape endpoint is exposed only on a secured
 * management port or network in deployment, never on the learner-facing chain (metrics are not learner data).
 */
@Component
class OperationalMetrics(private val jdbc: JdbcClient, private val clock: Clock, registry: MeterRegistry) {
    init {
        gauge(registry, "secdrill.outbox.backlog", "Unpublished outbox events") { count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL") }
        gauge(registry, "secdrill.outbox.oldest.age.seconds", "Age of the oldest unpublished outbox event") {
            ageSeconds("SELECT min(created_at) FROM outbox_events WHERE published_at IS NULL")
        }
        gauge(registry, "secdrill.outbox.publish.failures", "Unpublished outbox events with a recorded publish error") {
            count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND last_publish_error IS NOT NULL")
        }
        gauge(registry, "secdrill.labs.active", "Labs not yet cleaned up") { count("SELECT count(*) FROM labs WHERE cleanup_confirmed_at IS NULL") }
        gauge(registry, "secdrill.labs.cleanup.pending", "Labs asked to terminate but not yet confirmed clean") {
            count("SELECT count(*) FROM labs WHERE desired_state = 'TERMINATED' AND cleanup_confirmed_at IS NULL")
        }
        gauge(registry, "secdrill.grading.system.errors", "Active evaluations that ended in SYSTEM_ERROR") {
            count("SELECT count(*) FROM evaluations WHERE is_active AND verdict = 'SYSTEM_ERROR'")
        }
        gauge(registry, "secdrill.jobs.dispatch.timeouts", "Dispatched jobs that waited too long for a worker") {
            count("SELECT count(*) FROM jobs WHERE state = 'DISPATCHED' AND last_error = 'DISPATCH_TIMEOUT'")
        }
        gauge(registry, "secdrill.runners.quarantined", "Quarantined runners") { count("SELECT count(*) FROM runner_quarantine") }
    }

    private fun gauge(registry: MeterRegistry, name: String, description: String, value: () -> Double) {
        Gauge.builder(name, Supplier { value() }).description(description).strongReference(true).register(registry)
    }

    private fun count(sql: String): Double = jdbc.sql(sql).query(Long::class.java).single().toDouble()

    private fun ageSeconds(sql: String): Double = jdbc.sql(sql).query(OffsetDateTime::class.java).optional().orElse(null)
        ?.let { Duration.between(it.toInstant(), clock.instant()).seconds.coerceAtLeast(0).toDouble() } ?: 0.0
}

/**
 * Correlation id (24): every response carries `X-Request-Id`, and the same id is in the logging MDC (`requestId`)
 * for the duration of the request so structured logs can be joined to it. An incoming id is accepted only if it is
 * a short, safe token, so it cannot inject into logs or the response header. Runs before security so even rejected
 * requests are traceable.
 */
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val incoming = request.getHeader(HEADER)?.takeIf { it.length in 1..200 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        val id = incoming ?: UUID.randomUUID().toString()
        MDC.put("requestId", id)
        response.setHeader(HEADER, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("requestId")
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
    }
}

@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {
    @Bean
    fun requestIdFilter(): FilterRegistrationBean<RequestIdFilter> =
        FilterRegistrationBean(RequestIdFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE; addUrlPatterns("/*") }
}
