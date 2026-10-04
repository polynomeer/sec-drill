package secdrill.controlplane.submission

import org.springframework.boot.builder.SpringApplicationBuilder
import secdrill.controlplane.ControlPlaneApplication
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** The fake worker must never run outside local development (prompt 04: fake와 실제 실행 결과 구별). */
class FakeWorkerSafetyTest {
    private fun assertStartupFails(vararg profiles: String) {
        val arguments = arrayOf("--server.port=0", "--spring.flyway.enabled=false", "--secdrill.execution.fake-worker.enabled=true",
            "--spring.rabbitmq.listener.simple.auto-startup=false")
        try {
            SpringApplicationBuilder(ControlPlaneApplication::class.java).profiles(*profiles).run(*arguments).close()
            fail("startup should have failed for profiles=${profiles.toList()}")
        } catch (error: Exception) {
            val messages = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
            assertTrue("fake-worker.enabled requires the 'local' profile" in messages, messages)
        }
    }

    @Test
    fun `fake worker without the local profile fails startup`() = assertStartupFails()

    @Test
    fun `fake worker together with prod fails startup`() = assertStartupFails("local", "prod")
}
