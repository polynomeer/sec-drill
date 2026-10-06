package secdrill.controlplane.submission

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.platform.OutboxPublisher
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.execution.fake.FakeGradingWorker
import secdrill.execution.fake.FakeGradingWorker.FakeStep
import secdrill.execution.protocol.Ack
import secdrill.kernel.JobKind
import secdrill.kernel.Verdict
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Job lease, heartbeat, fencing, bounded retry and fake-result labelling (13, 16, 20; prompt 04). */
@IntegrationTest
@TestPropertySource(properties = ["test.context=job-lease"])
class JobLeaseTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var jobs: JobLeaseService
    @Autowired private lateinit var publisher: OutboxPublisher
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()

    /** Accepts a submission and waits until its GRADE job is claimable. */
    /** FLAG grading goes to the runner hosting the Lab (CtfGradingTest); generic workers get the other kinds. */
    /**
     * A DISPATCHED GRADE job on an OBJECTIVE submission. OBJECTIVE has no auto-grader (T14), so accept() creates no
     * job; this inserts one directly to exercise the generic lease/fencing mechanism (the specific graders reuse it
     * via JobLeaseService.claimJob/complete). OBJECTIVE is not excluded by claimNext, so a generic worker can claim it.
     */
    private fun dispatchedJob(kind: String = "OBJECTIVE"): Pair<UUID, UUID> {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val body = if (kind == "FLAG") Fixtures.flagBody(0) else """{"kind":"$kind","expectedVersion":0,"content":{}}"""
        val response = TestBrowser("http://127.0.0.1:$port").send(
            "POST", "/v1/sessions/$session/submissions", body = body, origin = TEST_ORIGIN, csrf = learner.login.csrfToken,
            cookies = learner.cookies, headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
        )
        assertEquals(202, response.status, response.body)
        val submission = UUID.fromString(json.readTree(response.body)["id"].asString())
        val job = fixtures.string("SELECT id::text FROM jobs WHERE submission_id = ?", submission)?.let(UUID::fromString) ?: run {
            val id = UUID.randomUUID()
            val now = clock.instant().atOffset(java.time.ZoneOffset.UTC)
            jdbc.sql(
                """INSERT INTO jobs(id, submission_id, session_id, kind, state, dispatched_at, due_at, created_at)
                   VALUES (?, ?, ?, 'GRADE', 'DISPATCHED', ?, ?, ?)""",
            ).params(id, submission, session, now, now, now).update()
            jdbc.sql("UPDATE submissions SET status = 'EVALUATING' WHERE id = ?").param(submission).update()
            id
        }
        publisher.publishOnce()
        fixtures.await { state(job) == "DISPATCHED" }
        // Each test owns exactly one claimable job: cancel leftovers from earlier tests in this context.
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE id <> ? AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
            .param(job).update()
        return submission to job
    }

    private fun state(job: UUID) = fixtures.string("SELECT state FROM jobs WHERE id = ?", job)
    private fun evaluations(submission: UUID) = fixtures.count("SELECT count(*) FROM evaluations WHERE submission_id = ?", submission)
    private fun activeVerdict(submission: UUID) = fixtures.string("SELECT verdict FROM evaluations WHERE submission_id = ? AND is_active", submission)
    private fun claimOnly(job: UUID) = assertNotNull(jobs.claimNext("worker-${UUID.randomUUID()}", setOf(JobKind.GRADE))).also { assertEquals(job, it.jobId) }
    private fun staleAudits(job: UUID) = fixtures.count("SELECT count(*) FROM audit_events WHERE actor_type = 'SYSTEM' AND action LIKE ?", "%job $job%")

    @Test
    fun `fake worker result is committed once and labelled as fake`() {
        val (submission, job) = dispatchedJob()
        assertNotNull(FakeGradingWorker(jobs).runOnce())
        assertEquals("SUCCEEDED", state(job))
        assertEquals(1, evaluations(submission))
        assertEquals("FAIL", activeVerdict(submission), "fake defaults to FAIL so it can never grant success")
        assertEquals(FakeGradingWorker.POLICY, fixtures.string("SELECT policy_version FROM evaluations WHERE submission_id = ?", submission))
        assertEquals("true", fixtures.string("SELECT demo::text FROM evaluations WHERE submission_id = ?", submission))
        assertEquals("[]", fixtures.string("SELECT dimensions::text FROM evaluations WHERE submission_id = ?", submission))
        assertEquals("SIMULATED", fixtures.string("SELECT trust_level FROM evidence WHERE event_type = 'EvaluationCommitted' AND safe_payload->>'submissionId' = ?", submission.toString()))
        assertEquals("EVALUATED", fixtures.string("SELECT status FROM submissions WHERE id = ?", submission))
        assertEquals(1, fixtures.count("SELECT count(*) FROM outbox_events WHERE event_type = 'EvaluationCommitted' AND aggregate_id = ?", submission))
        assertNull(jobs.claimNext("another", setOf(JobKind.GRADE))?.takeIf { it.jobId == job }, "a finished job is not claimable")
    }

    @Test
    fun `result after lease expiry is rejected, audited and never becomes an evaluation`() {
        val (submission, job) = dispatchedJob()
        val first = claimOnly(job)
        assertEquals(Ack.ACCEPTED, jobs.start(first))
        clock.advance(Duration.ofSeconds(31))
        val report = FakeGradingWorker(jobs).report(first, FakeStep.Complete(Verdict.PASS))
        assertEquals(Ack.STALE, jobs.complete(first, report))
        assertEquals(0, evaluations(submission))
        assertTrue(staleAudits(job) >= 1)

        jobs.sweepOnce()
        assertEquals("RETRY_WAIT", state(job))
        assertEquals("LEASE_EXPIRED", fixtures.string("SELECT last_error FROM jobs WHERE id = ?", job))
        clock.advance(Duration.ofSeconds(7))
        jobs.sweepOnce()
        val second = claimOnly(job)
        assertEquals(first.fencingToken + 1, second.fencingToken)
        assertEquals(2, second.attempt)

        // The old worker wakes up: its token is no longer current.
        assertEquals(Ack.STALE, jobs.heartbeat(first))
        assertEquals(Ack.ACCEPTED, jobs.start(second))
        assertEquals(Ack.STALE, jobs.complete(first, report))
        assertEquals(Ack.ACCEPTED, jobs.complete(second, FakeGradingWorker(jobs).report(second, FakeStep.Complete(Verdict.FAIL))))
        assertEquals(1, evaluations(submission))
        assertEquals("FAIL", activeVerdict(submission))
    }

    @Test
    fun `heartbeat extends the lease`() {
        val (submission, job) = dispatchedJob()
        val lease = claimOnly(job)
        assertEquals(Ack.ACCEPTED, jobs.start(lease))
        clock.advance(Duration.ofSeconds(20))
        assertEquals(Ack.ACCEPTED, jobs.heartbeat(lease))
        clock.advance(Duration.ofSeconds(20))
        assertEquals(0, jobs.sweepOnce(), "lease must not expire after a heartbeat")
        assertEquals(Ack.ACCEPTED, jobs.complete(lease, FakeGradingWorker(jobs).report(lease, FakeStep.Complete(Verdict.FAIL))))
        assertEquals(1, evaluations(submission))
    }

    @Test
    fun `platform errors retry with backoff and end as SYSTEM_ERROR, never FAIL`() {
        val (submission, job) = dispatchedJob()
        val worker = FakeGradingWorker(jobs) { FakeStep.PlatformError }
        val dueDelays = mutableListOf<Long>()
        repeat(3) { attempt ->
            val now = clock.instant()
            assertNotNull(worker.runOnce())
            if (attempt < 2) {
                assertEquals("RETRY_WAIT", state(job))
                val due = jdbc.sql("SELECT due_at FROM jobs WHERE id = ?").param(job).query(OffsetDateTime::class.java).single().toInstant()
                dueDelays += Duration.between(now, due).seconds
                clock.advance(Duration.ofSeconds(25))
                jobs.sweepOnce()
                assertEquals("DISPATCHED", state(job))
            }
        }
        assertTrue(dueDelays[0] in 5..6 && dueDelays[1] in 20..21, "delays were $dueDelays")
        assertEquals("FAILED", state(job))
        assertEquals(3, fixtures.count("SELECT attempt FROM jobs WHERE id = ?", job))
        assertEquals("SYSTEM_ERROR", activeVerdict(submission))
        assertEquals("EVALUATION_FAILED", fixtures.string("SELECT status FROM submissions WHERE id = ?", submission))
        assertEquals(0, fixtures.count("SELECT count(*) FROM evaluations WHERE submission_id = ? AND verdict = 'FAIL'", submission))
    }

    @Test
    fun `invalid content fails immediately as SYSTEM_ERROR without retry`() {
        // PATCH grading and its INCONCLUSIVE patch gate are covered by PatchGradingTest (only grading runners take PATCH jobs).
        val (submission, job) = dispatchedJob()
        FakeGradingWorker(jobs) { FakeStep.ContentInvalid }.runOnce()
        assertEquals("FAILED", state(job))
        assertEquals(1, fixtures.count("SELECT attempt FROM jobs WHERE id = ?", job))
        assertEquals("SYSTEM_ERROR", activeVerdict(submission))
    }

    @Test
    fun `abandoned leases expire three times and end as SYSTEM_ERROR`() {
        val (submission, job) = dispatchedJob()
        val crashing = FakeGradingWorker(jobs) { FakeStep.Abandon }
        repeat(3) {
            assertNotNull(crashing.runOnce())
            clock.advance(Duration.ofSeconds(31))
            jobs.sweepOnce()
            clock.advance(Duration.ofSeconds(25))
            jobs.sweepOnce()
        }
        assertEquals("FAILED", state(job))
        assertEquals("LEASE_EXPIRED", fixtures.string("SELECT last_error FROM jobs WHERE id = ?", job))
        assertEquals("SYSTEM_ERROR", activeVerdict(submission))
    }

    @Test
    fun `dispatch wait beyond 120 seconds is flagged without reissuing the job`() {
        val (_, job) = dispatchedJob()
        clock.advance(Duration.ofSeconds(121))
        jobs.sweepOnce()
        assertEquals("DISPATCHED", state(job))
        assertEquals("DISPATCH_TIMEOUT", fixtures.string("SELECT last_error FROM jobs WHERE id = ?", job))
        assertEquals(1, fixtures.count("SELECT count(*) FROM audit_events WHERE purpose = 'job dispatch timeout' AND action LIKE ?", "%job $job%"))
    }
}
