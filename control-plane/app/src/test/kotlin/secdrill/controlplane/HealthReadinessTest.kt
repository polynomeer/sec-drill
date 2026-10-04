package secdrill.controlplane

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import secdrill.controlplane.support.TestPostgres
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/** The app starts on a migrated PostgreSQL; readiness follows the database, liveness does not. */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.flyway.enabled=true", "spring.rabbitmq.listener.simple.auto-startup=false", "secdrill.async.scheduling-enabled=false"],
)
class HealthReadinessTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = TestPostgres.container()
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val http = HttpClient.newHttpClient()

    private fun status(path: String): Int =
        http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).build(), HttpResponse.BodyHandlers.discarding())
            .statusCode()

    @Test
    fun `readiness requires the database while liveness does not`() {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Int::class.java))
        assertEquals(200, status("/actuator/health/readiness"))
        assertEquals(200, status("/actuator/health/liveness"))

        postgres.stop()

        assertEquals(503, status("/actuator/health/readiness"))
        assertEquals(200, status("/actuator/health/liveness"))
    }
}
