package secdrill.controlplane.submission

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import secdrill.controlplane.platform.FaultPoints
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** A crash after every insert but before commit leaves nothing behind (prompt 04: 커밋 전 장애). */
@IntegrationTest
@Import(CrashBeforeCommitTest.Faults::class)
class CrashBeforeCommitTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var faults: ArmableFaults

    class ArmableFaults : FaultPoints() {
        @Volatile var armed: String? = null
        override fun onReach(point: String) {
            if (point == armed) throw IllegalStateException("injected crash at $point")
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Faults {
        @Bean
        @Primary
        fun armableFaults() = ArmableFaults()
    }

    @Test
    fun `crash before commit stores neither submission nor outbox event`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val key = UUID.randomUUID().toString()
        val browser = TestBrowser("http://127.0.0.1:$port")
        fun submit() = browser.send("POST", "/v1/sessions/$session/submissions", body = Fixtures.flagBody(0), origin = TEST_ORIGIN,
            csrf = learner.login.csrfToken, cookies = learner.cookies, headers = mapOf("Idempotency-Key" to key))

        faults.armed = SubmissionService.FAULT_BEFORE_COMMIT
        val crashed = submit()
        assertEquals(500, crashed.status, crashed.body)
        mapOf(
            "submissions" to "SELECT count(*) FROM submissions WHERE session_id = ?",
            "jobs" to "SELECT count(*) FROM jobs WHERE session_id = ?",
            "evidence" to "SELECT count(*) FROM evidence WHERE session_id = ?",
            "ledger_heads" to "SELECT count(*) FROM ledger_heads WHERE session_id = ?",
            "outbox_events" to "SELECT count(*) FROM outbox_events WHERE (envelope->>'sessionId')::uuid = ?",
        ).forEach { (table, sql) -> assertEquals(0, fixtures.count(sql, session), table) }
        assertEquals(0, fixtures.count("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", UUID.fromString(key)))
        assertEquals(0, fixtures.count("SELECT version FROM sessions WHERE id = ?", session))

        faults.armed = null
        assertEquals(202, submit().status, "the same key works once the crash is gone")
    }
}
