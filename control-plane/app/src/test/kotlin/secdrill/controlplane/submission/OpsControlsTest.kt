package secdrill.controlplane.submission

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.lab.LabCalls
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.JobOutcome
import secdrill.execution.protocol.JobResultReport
import secdrill.kernel.JobKind
import secdrill.kernel.OperatorRole
import secdrill.kernel.Verdict
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Operations controls (T14 ops, 19, 25): Lab pool drain and Runner quarantine, over the API and the lease path. */
@IntegrationTest
@TestPropertySource(properties = ["test.context=ops-controls"])
class OpsControlsTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var jobs: JobLeaseService
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var jdbc: JdbcClient

    private lateinit var browser: TestBrowser

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
    }

    private fun adminToken() = operators.issue(UUID.randomUUID(), OperatorRole.SECURITY_ADMIN, "ops controls", Duration.ofHours(1))

    /** A DISPATCHED REPORT job (no submission or lab, so it satisfies jobs_check) to exercise the generic lease path. */
    private fun dispatchedJob(): UUID {
        val session = fixtures.activeSession(fixtures.learner().userId)
        val id = UUID.randomUUID()
        val now = jdbc.sql("SELECT now()").query(java.time.OffsetDateTime::class.java).single()
        jdbc.sql("INSERT INTO jobs(id, session_id, kind, state, dispatched_at, due_at, created_at) VALUES (?, ?, 'REPORT', 'DISPATCHED', ?, ?, ?)")
            .params(id, session, now, now, now).update()
        return id
    }

    @Test
    fun `draining the pool pauses new Labs and resuming restores them`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val admin = adminToken()

        assertEquals(204, browser.send("POST", "/ops/v1/lab-pool/drain", body = """{"draining":true}""", bearer = admin, origin = TEST_ORIGIN).status)
        assertEquals(503, LabCalls.requestLab(browser, learner, session).status, "a draining pool refuses new Labs")

        assertEquals(204, browser.send("POST", "/ops/v1/lab-pool/drain", body = """{"draining":false}""", bearer = admin, origin = TEST_ORIGIN).status)
        assertEquals(202, LabCalls.requestLab(browser, learner, session).status, "resuming accepts new Labs")
    }

    @Test
    fun `a quarantined runner cannot claim, and releasing restores it`() {
        val job = dispatchedJob()
        val admin = adminToken()
        assertEquals(204, browser.send("POST", "/ops/v1/runners/runner-q1/quarantine", body = """{"reason":"host lost"}""", bearer = admin, origin = TEST_ORIGIN).status)

        assertNull(jobs.claimNext("runner-q1", setOf(JobKind.REPORT)), "a quarantined runner gets no work")

        assertEquals(204, browser.send("POST", "/ops/v1/runners/runner-q1/release", bearer = admin, origin = TEST_ORIGIN).status)
        assertNotNull(jobs.claimNext("runner-q1", setOf(JobKind.REPORT)), "a released runner can claim again")
        assertEquals(job, jdbc.sql("SELECT id FROM jobs WHERE worker_id = 'runner-q1'").query(UUID::class.java).single())
    }

    @Test
    fun `a runner quarantined mid-job has its heartbeat and result rejected`() {
        dispatchedJob()
        val lease = jobs.claimNext("runner-q2", setOf(JobKind.REPORT))!!
        assertEquals(Ack.ACCEPTED, jobs.start(lease))
        jobs.quarantineRunner("runner-q2", "escape suspected")

        assertEquals(Ack.STALE, jobs.heartbeat(lease), "a quarantined runner cannot extend its lease")
        assertEquals(Ack.STALE, jobs.complete(lease, JobResultReport(JobOutcome.COMPLETED, Verdict.PASS, "d".repeat(64), "p", fake = true, gates = emptyList())))
    }

    @Test
    fun `only an operator role may drive the controls`() {
        val learnerToken = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author", Duration.ofHours(1))
        assertEquals(403, browser.send("POST", "/ops/v1/lab-pool/drain", body = """{"draining":true}""", bearer = learnerToken, origin = TEST_ORIGIN).status)
        assertEquals(403, browser.send("POST", "/ops/v1/runners/x/quarantine", body = """{"reason":"nope"}""", bearer = learnerToken, origin = TEST_ORIGIN).status)
    }
}
