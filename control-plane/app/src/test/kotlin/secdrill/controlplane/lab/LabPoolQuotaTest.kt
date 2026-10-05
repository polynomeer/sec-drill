package secdrill.controlplane.lab

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pool quota (00: at most 20 real Labs) with a small pool in its own context. */
@IntegrationTest
@TestPropertySource(properties = ["secdrill.lab.pool-max=2", "test.context=lab-pool"])
class LabPoolQuotaTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures

    @Test
    fun `the pool limit is shared by all users`() {
        val browser = TestBrowser("http://127.0.0.1:$port")
        repeat(2) {
            val learner = fixtures.learner()
            assertEquals(202, LabCalls.requestLab(browser, learner, fixtures.activeSession(learner.userId)).status)
        }
        val third = fixtures.learner()
        assertEquals(429, LabCalls.requestLab(browser, third, fixtures.activeSession(third.userId)).status)
    }
}
