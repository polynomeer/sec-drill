package secdrill.controlplane.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.utility.DockerImageName
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
    properties = [
        "spring.flyway.enabled=true",
        "secdrill.auth.allowed-origins=$TEST_ORIGIN",
        // Tests drive the publisher and sweeper step by step.
        "secdrill.async.scheduling-enabled=false",
    ],
)
@Import(TestInfrastructure::class)
annotation class IntegrationTest

@TestConfiguration(proxyBeanMethods = false)
class TestInfrastructure {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = TestPostgres.container()

    /** rabbitmq:4.3.6-alpine pinned by index digest (D-08). */
    @Bean
    @ServiceConnection
    fun rabbit(): RabbitMQContainer = RabbitMQContainer(
        DockerImageName.parse("rabbitmq@sha256:2cb43283d8bbd3caa6c0f0dc00e96c8de772df33df7fd8af6aee4d605aeb8ada")
            .asCompatibleSubstituteFor("rabbitmq"),
    )

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
