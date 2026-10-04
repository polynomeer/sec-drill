package secdrill.controlplane.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

const val TEST_ORIGIN = "https://app.secdrill.test"

/** Full application on a migrated PostgreSQL with a controllable clock. Contexts with equal config are reused. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.enabled=true", "secdrill.auth.allowed-origins=$TEST_ORIGIN"],
)
@Import(TestInfrastructure::class)
annotation class IntegrationTest

@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = TestPostgres.container()

    @Bean
    @Primary
    fun testClock(): MutableClock = MutableClock(Instant.parse("2026-10-04T00:00:00Z"))
}

class MutableClock(@Volatile private var now: Instant) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
