package secdrill.controlplane.submission

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.platform.AsyncProperties
import secdrill.controlplane.platform.OutboxWriter
import secdrill.controlplane.platform.SystemAudit
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.JobControl
import secdrill.execution.protocol.JobLease
import secdrill.execution.protocol.JobOutcome
import secdrill.execution.protocol.JobResultReport
import secdrill.kernel.EventType
import secdrill.kernel.EvidenceSource
import secdrill.kernel.JobFailure
import secdrill.kernel.JobKind
import secdrill.kernel.JobState
import secdrill.kernel.PatchGate
import secdrill.kernel.SubmissionKind
import secdrill.kernel.SubmissionStatus
import secdrill.kernel.TrustLevel
import secdrill.kernel.Verdict
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random

/**
 * Server-managed job leases with fencing (13, 16, ADR-004).
 *
 * - claim: DISPATCHED -> LEASED, attempt + 1, fencing token + 1, lease = now + 30 s
 * - start / heartbeat / complete are accepted only for the current token, in the right state, before lease expiry
 * - platform errors and expired leases retry up to three attempts in total, then become SYSTEM_ERROR, never FAIL
 * - stale results are audited and ignored; they never create an evaluation
 */
@Service
class JobLeaseService(
    private val jdbc: JdbcClient,
    private val properties: AsyncProperties,
    private val ledger: LedgerAppender,
    private val outbox: OutboxWriter,
    private val audit: SystemAudit,
    private val json: JsonMapper,
    private val clock: Clock,
) : JobControl {
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private fun Instant.db(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    /**
     * Generic workers (the fake worker) never receive FLAG or PATCH grading: FLAG needs the runner hosting the Lab to
     * observe the objective ([CtfGradingService]), PATCH needs a grading runtime ([PatchGradingService]), DETECTION
     * is scored on the hidden holdout by [DetectionGradingService].
     */
    @Transactional
    override fun claimNext(workerId: String, kinds: Set<JobKind>): JobLease? {
        if (kinds.isEmpty() || quarantined(workerId)) return null
        val row = jdbc.sql(
            """SELECT j.id, j.kind, j.attempt, j.fencing_token, j.submission_id, s.request_digest FROM jobs j
               LEFT JOIN submissions s ON s.id = j.submission_id
               WHERE j.state = 'DISPATCHED' AND j.kind IN (:kinds) AND j.attempt < :max AND (s.kind IS NULL OR s.kind NOT IN ('FLAG', 'PATCH', 'DETECTION', 'OBJECTIVE'))
               ORDER BY j.dispatched_at, j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED""",
        ).param("kinds", kinds.map { it.name }).param("max", properties.maxAttempts)
            .query { rs, _ -> claimRow(rs) }.optional().orElse(null) ?: return null
        return lease(row, workerId)
    }

    /** Leases one specific DISPATCHED job if it is still claimable (used by graders that choose their own jobs). */
    @Transactional
    fun claimJob(jobId: UUID, workerId: String): JobLease? {
        if (quarantined(workerId)) return null
        val row = jdbc.sql(
            """SELECT j.id, j.kind, j.attempt, j.fencing_token, j.submission_id, s.request_digest FROM jobs j
               LEFT JOIN submissions s ON s.id = j.submission_id
               WHERE j.id = ? AND j.state = 'DISPATCHED' AND j.attempt < ? FOR UPDATE OF j SKIP LOCKED""",
        ).params(jobId, properties.maxAttempts).query { rs, _ -> claimRow(rs) }.optional().orElse(null) ?: return null
        return lease(row, workerId)
    }

    /** The worker that holds the current lease of [lease]'s job, if any. */
    fun leaseHolder(lease: JobLease): String? = jdbc.sql("SELECT worker_id FROM jobs WHERE id = ? AND fencing_token = ?")
        .params(lease.jobId, lease.fencingToken).query(String::class.java).optional().orElse(null)

    /** A quarantined Runner (19, 25) gets no new claims and no accepted results. */
    fun quarantined(workerId: String): Boolean =
        jdbc.sql("SELECT count(*) FROM runner_quarantine WHERE runner_id = ?").param(workerId).query(Int::class.java).single() > 0

    @Transactional
    fun quarantineRunner(runnerId: String, reason: String) {
        jdbc.sql(
            """INSERT INTO runner_quarantine(runner_id, reason, quarantined_at) VALUES (?, ?, ?)
               ON CONFLICT (runner_id) DO UPDATE SET reason = excluded.reason, quarantined_at = excluded.quarantined_at""",
        ).params(runnerId, reason.take(500), now().db()).update()
        audit.record("runner quarantine", "runner $runnerId quarantined: ${reason.take(200)}")
    }

    @Transactional
    fun releaseRunner(runnerId: String) {
        if (jdbc.sql("DELETE FROM runner_quarantine WHERE runner_id = ?").param(runnerId).update() > 0) {
            audit.record("runner release", "runner $runnerId released from quarantine")
        }
    }

    private fun claimRow(rs: java.sql.ResultSet) = ClaimRow(
        rs.getObject(1, UUID::class.java), JobKind.valueOf(rs.getString(2)), rs.getInt(3), rs.getLong(4),
        rs.getObject(5, UUID::class.java), rs.getString(6),
    )

    private fun lease(row: ClaimRow, workerId: String): JobLease {
        val now = now()
        val leaseUntil = now.plus(properties.lease)
        jdbc.sql(
            """UPDATE jobs SET state = 'LEASED', attempt = attempt + 1, fencing_token = fencing_token + 1, worker_id = ?,
               lease_until = ?, version = version + 1 WHERE id = ?""",
        ).params(workerId.take(200), leaseUntil.db(), row.id).update()
        row.submissionId?.let {
            jdbc.sql("UPDATE submissions SET status = 'EVALUATING' WHERE id = ? AND status = 'ACCEPTED'").param(it).update()
        }
        return JobLease(row.id, row.kind, row.attempt + 1, row.token + 1, leaseUntil, row.submissionId, row.digest)
    }

    @Transactional
    override fun start(lease: JobLease): Ack = acceptIf(
        lease, "start",
        jdbc.sql(
            """UPDATE jobs SET state = 'RUNNING', version = version + 1 WHERE id = ? AND fencing_token = ? AND state = 'LEASED' AND lease_until > ?
               AND NOT EXISTS (SELECT 1 FROM runner_quarantine q WHERE q.runner_id = worker_id)""",
        ).params(lease.jobId, lease.fencingToken, now().db()).update(),
    )

    @Transactional
    override fun heartbeat(lease: JobLease): Ack {
        val now = now()
        return acceptIf(
            lease, "heartbeat",
            jdbc.sql(
                """UPDATE jobs SET lease_until = ? WHERE id = ? AND fencing_token = ? AND state IN ('LEASED', 'RUNNING') AND lease_until > ?
                   AND NOT EXISTS (SELECT 1 FROM runner_quarantine q WHERE q.runner_id = worker_id)""",
            ).params(now.plus(properties.lease).db(), lease.jobId, lease.fencingToken, now.db()).update(),
        )
    }

    @Transactional
    override fun complete(lease: JobLease, report: JobResultReport): Ack {
        val now = now()
        val job = lockJob(lease.jobId) ?: return stale(lease, "complete")
        if (job.state != JobState.RUNNING || job.token != lease.fencingToken || !job.leaseUntil!!.isAfter(now) ||
            (job.workerId != null && quarantined(job.workerId))
        ) {
            return stale(lease, "complete")
        }
        when (report.outcome) {
            JobOutcome.COMPLETED -> {
                jdbc.sql(
                    """UPDATE jobs SET state = 'SUCCEEDED', result_digest = ?, worker_id = NULL, lease_until = NULL, version = version + 1
                       WHERE id = ?""",
                ).params(report.resultDigest, job.id).update()
                val evaluation = commitEvaluation(job, report.verdict!!, report.policyVersion, report.fake, report.gates, report.unverifiedIsolation, report.dimensions)
                outbox.append(
                    EventType.ExecutionCompleted, job.id, job.version + 1, job.sessionId, job.submissionId ?: job.id,
                    mapOf("jobId" to job.id.toString(), "attempt" to job.attempt, "fencingToken" to job.token, "resultDigest" to report.resultDigest),
                    causationId = evaluation,
                )
            }
            JobOutcome.PLATFORM_ERROR -> retryOrFail(job, JobFailure.PLATFORM_ERROR, report.policyVersion, report.fake)
            JobOutcome.CONTENT_INVALID -> failFinally(job, JobFailure.CONTENT_INVALID, report.policyVersion, report.fake)
            JobOutcome.INCONCLUSIVE -> {
                // The grader ran; the objective could not be confirmed. SYSTEM_ERROR + INCONCLUSIVE gate, never FAIL (09).
                jdbc.sql("UPDATE jobs SET state = 'SUCCEEDED', result_digest = ?, worker_id = NULL, lease_until = NULL, version = version + 1 WHERE id = ?")
                    .params(report.resultDigest, job.id).update()
                commitEvaluation(job, Verdict.SYSTEM_ERROR, report.policyVersion, report.fake, report.gates, report.unverifiedIsolation, report.dimensions)
            }
        }
        return Ack.ACCEPTED
    }

    @Scheduled(fixedDelayString = "\${secdrill.async.sweep-interval:1s}")
    fun scheduledSweep() {
        if (properties.schedulingEnabled) sweepOnce()
    }

    /** Expires leases, releases due retries and flags dispatch timeouts. Returns how many jobs changed. */
    @Transactional
    fun sweepOnce(): Int {
        val now = now()
        var changed = 0
        jdbc.sql("SELECT id FROM jobs WHERE state IN ('LEASED', 'RUNNING') AND lease_until <= ? ORDER BY lease_until FOR UPDATE SKIP LOCKED")
            .param(now.db()).query(UUID::class.java).list()
            .forEach { id -> lockJob(id!!)?.let { retryOrFail(it, JobFailure.LEASE_EXPIRED, null, fake = false); changed++ } }
        changed += jdbc.sql(
            "UPDATE jobs SET state = 'DISPATCHED', dispatched_at = ?, version = version + 1 WHERE state = 'RETRY_WAIT' AND due_at <= ?",
        ).params(now.db(), now.db()).update()
        // Dispatch waiting is not a lease (13): flag it for operators instead of re-issuing work to a live pool.
        jdbc.sql(
            """UPDATE jobs SET last_error = 'DISPATCH_TIMEOUT' WHERE state = 'DISPATCHED' AND dispatched_at <= ?
               AND last_error IS DISTINCT FROM 'DISPATCH_TIMEOUT' RETURNING id""",
        ).param(now.minus(properties.dispatchTimeout).db()).query(UUID::class.java).list().forEach {
            audit.record("job dispatch timeout", "job $it waited longer than ${properties.dispatchTimeout} for a worker")
        }
        return changed
    }

    private fun retryOrFail(job: LockedJob, failure: JobFailure, policy: String?, fake: Boolean) {
        if (job.attempt >= properties.maxAttempts) return failFinally(job, failure, policy, fake)
        val delay = properties.retryDelays[(job.attempt - 1).coerceIn(0, properties.retryDelays.lastIndex)]
        val jitter = Duration.ofMillis(Random.nextLong(properties.retryJitter.toMillis() + 1))
        jdbc.sql(
            """UPDATE jobs SET state = 'RETRY_WAIT', due_at = ?, last_error = ?, worker_id = NULL, lease_until = NULL, version = version + 1
               WHERE id = ?""",
        ).params(now().plus(delay).plus(jitter).db(), failure.name, job.id).update()
    }

    /** Platform failure after the last attempt: SYSTEM_ERROR evaluation, never negative skill evidence (00). */
    private fun failFinally(job: LockedJob, failure: JobFailure, policy: String?, fake: Boolean) {
        jdbc.sql("UPDATE jobs SET state = 'FAILED', last_error = ?, worker_id = NULL, lease_until = NULL, version = version + 1 WHERE id = ?")
            .params(failure.name, job.id).update()
        if (job.submissionId != null) commitEvaluation(job, Verdict.SYSTEM_ERROR, policy ?: "platform/system-error", fake)
    }

    /** Inserts the next active EvaluationRevision and records it in the ledger and outbox in this transaction. */
    private fun commitEvaluation(
        job: LockedJob, verdict: Verdict, policy: String, fake: Boolean,
        gates: List<secdrill.execution.protocol.GateReport> = emptyList(), unverifiedIsolation: Boolean = false,
        dimensions: List<secdrill.execution.protocol.DimensionReport> = emptyList(),
    ): UUID {
        val demo = fake || unverifiedIsolation
        val submission = checkNotNull(job.submissionId)
        val kind = jdbc.sql("SELECT kind FROM submissions WHERE id = ?").param(submission).query { rs, _ -> SubmissionKind.valueOf(rs.getString(1)) }.single()
        val revision = jdbc.sql("SELECT coalesce(max(revision), 0) + 1 FROM evaluations WHERE submission_id = ?").param(submission).query(Int::class.java).single()
        val patchGate = if (kind != SubmissionKind.PATCH) null else when (verdict) {
            Verdict.PASS -> PatchGate.VERIFIED
            Verdict.FAIL -> PatchGate.NOT_VERIFIED
            Verdict.SYSTEM_ERROR -> PatchGate.INCONCLUSIVE
        }
        val evaluationId = UUID.randomUUID()
        val superseded = jdbc.sql("UPDATE evaluations SET is_active = false WHERE submission_id = ? AND is_active RETURNING id")
            .param(submission).query(UUID::class.java).optional().orElse(null)
        jdbc.sql(
            """INSERT INTO evaluations(id, submission_id, revision, policy_version, verdict, patch_gate, dimensions, gates, supersedes_id, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""",
        ).params(
            // The List overload accepts nulls (patch gate and supersedes are optional).
            listOf(evaluationId, submission, revision, policy, verdict.name, patchGate?.name,
                json.writeValueAsString(dimensions.map { mapOf("key" to it.key, "scoreBps" to it.scoreBps) }),
                json.writeValueAsString(gates.map { mapOf("key" to it.key, "result" to it.result.name) }), superseded, now().db()),
        ).update()
        jdbc.sql("UPDATE evaluations SET demo = ? WHERE id = ?").params(demo, evaluationId).update()
        // A re-grade of a finished Session queues a new report revision; the earlier revision stays as it was (10).
        jdbc.sql(
            """INSERT INTO jobs(id, session_id, kind, state, dispatched_at, due_at, created_at)
               SELECT ?, id, 'REPORT', 'DISPATCHED', ?, ?, ? FROM sessions WHERE id = ? AND status = 'COMPLETED'""",
        ).params(UUID.randomUUID(), now().db(), now().db(), now().db(), job.sessionId).update()
        val status = if (verdict == Verdict.SYSTEM_ERROR) SubmissionStatus.EVALUATION_FAILED else SubmissionStatus.EVALUATED
        jdbc.sql("UPDATE submissions SET status = ? WHERE id = ?").params(status.name, submission).update()
        // A fake worker observed nothing: its result is SIMULATED evidence, never SERVER_VERIFIED.
        val evidence = ledger.append(
            job.sessionId, EventType.EvaluationCommitted.name, EvidenceSource.SUPERVISOR,
            if (fake) TrustLevel.SIMULATED else TrustLevel.SERVER_VERIFIED,
            mapOf("submissionId" to submission, "evaluationId" to evaluationId, "revision" to revision, "verdict" to verdict.name, "fake" to fake, "demo" to demo),
        )
        outbox.append(
            EventType.EvaluationCommitted, submission, revision.toLong(), job.sessionId, submission,
            mapOf("submissionId" to submission.toString(), "revision" to revision, "evidenceIds" to listOf(evidence.evidenceId.toString())),
            seq = evidence.seq,
        )
        return evaluationId
    }

    private fun lockJob(id: UUID): LockedJob? = jdbc.sql(
        "SELECT id, session_id, submission_id, state, attempt, fencing_token, lease_until, version, worker_id FROM jobs WHERE id = ? FOR UPDATE",
    ).param(id).query { rs, _ ->
        LockedJob(
            rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java),
            JobState.valueOf(rs.getString(4)), rs.getInt(5), rs.getLong(6),
            rs.getObject(7, OffsetDateTime::class.java)?.toInstant(), rs.getLong(8), rs.getString(9),
        )
    }.optional().orElse(null)

    private fun acceptIf(lease: JobLease, action: String, updated: Int): Ack = if (updated == 1) Ack.ACCEPTED else stale(lease, action)

    private fun stale(lease: JobLease, action: String): Ack {
        audit.record("stale job result", "rejected $action for job ${lease.jobId} attempt ${lease.attempt} token ${lease.fencingToken}")
        return Ack.STALE
    }

    private data class ClaimRow(val id: UUID, val kind: JobKind, val attempt: Int, val token: Long, val submissionId: UUID?, val digest: String?)

    private data class LockedJob(
        val id: UUID, val sessionId: UUID, val submissionId: UUID?, val state: JobState,
        val attempt: Int, val token: Long, val leaseUntil: Instant?, val version: Long, val workerId: String?,
    )
}

/**
 * Operator re-grade (10, 15 `/ops/rejudge-requests` simplified): a new GRADE job revision for the same submission.
 * The resulting evaluation is a new revision; earlier revisions and reports are kept. Audited.
 */
@org.springframework.web.bind.annotation.RestController
class RejudgeController(private val jdbc: JdbcClient, private val audit: SystemAudit, private val clock: Clock) {
    @org.springframework.web.bind.annotation.PostMapping("/ops/v1/submissions/{id}/rejudge")
    @Transactional
    fun rejudge(
        @org.springframework.security.core.annotation.AuthenticationPrincipal principal: secdrill.controlplane.identity.OperatorPrincipal,
        @org.springframework.web.bind.annotation.PathVariable id: UUID,
    ): Map<String, Any> {
        if (principal.role !in setOf(secdrill.kernel.OperatorRole.OPERATOR, secdrill.kernel.OperatorRole.SECURITY_ADMIN)) {
            throw secdrill.kernel.ApiException(secdrill.kernel.ErrorCode.FORBIDDEN, "Role is not allowed to re-grade")
        }
        val session = jdbc.sql("SELECT session_id FROM submissions WHERE id = ?").param(id).query(UUID::class.java).optional()
            .orElseThrow { secdrill.controlplane.access.ResourceNotFoundException() }
        if (jdbc.sql("SELECT count(*) FROM jobs WHERE submission_id = ? AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')").param(id).query(Int::class.java).single() > 0) {
            throw secdrill.kernel.ApiException(secdrill.kernel.ErrorCode.INVALID_STATE, "A grading job for this submission is still running")
        }
        val revision = jdbc.sql("SELECT coalesce(max(revision), 0) + 1 FROM jobs WHERE submission_id = ? AND kind = 'GRADE'").param(id).query(Int::class.java).single()
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val job = UUID.randomUUID()
        jdbc.sql("INSERT INTO jobs(id, submission_id, session_id, kind, revision, state, dispatched_at, due_at, created_at) VALUES (?, ?, ?, 'GRADE', ?, 'DISPATCHED', ?, ?, ?)")
            .params(job, id, session, revision, now, now, now).update()
        audit.record("rejudge", "operator ${principal.operatorId} re-graded submission $id as job revision $revision")
        return mapOf("jobId" to job.toString(), "revision" to revision)
    }
}

/**
 * Runner quarantine (19, 25 "Runner 유실"): an operator marks a Runner so it can no longer claim work or have its
 * results accepted, then releases it after the node is re-enrolled on a clean host.
 */
@org.springframework.web.bind.annotation.RestController
class RunnerOpsController(private val jobs: JobLeaseService) {
    @org.springframework.web.bind.annotation.PostMapping("/ops/v1/runners/{runnerId}/quarantine", consumes = ["application/json"])
    fun quarantine(
        @org.springframework.security.core.annotation.AuthenticationPrincipal principal: secdrill.controlplane.identity.OperatorPrincipal,
        @org.springframework.web.bind.annotation.PathVariable runnerId: String,
        @org.springframework.web.bind.annotation.RequestBody body: tools.jackson.databind.JsonNode,
    ): org.springframework.http.ResponseEntity<Void> {
        requireOps(principal)
        val reason = body["reason"]?.takeIf { it.isString }?.asString()?.takeIf { it.length in 3..500 }
            ?: throw secdrill.kernel.ApiException(secdrill.kernel.ErrorCode.VALIDATION_FAILED, "reason must be 3 to 500 characters")
        jobs.quarantineRunner(runnerId, reason)
        return org.springframework.http.ResponseEntity.noContent().build()
    }

    @org.springframework.web.bind.annotation.PostMapping("/ops/v1/runners/{runnerId}/release")
    fun release(
        @org.springframework.security.core.annotation.AuthenticationPrincipal principal: secdrill.controlplane.identity.OperatorPrincipal,
        @org.springframework.web.bind.annotation.PathVariable runnerId: String,
    ): org.springframework.http.ResponseEntity<Void> {
        requireOps(principal)
        jobs.releaseRunner(runnerId)
        return org.springframework.http.ResponseEntity.noContent().build()
    }

    private fun requireOps(principal: secdrill.controlplane.identity.OperatorPrincipal) {
        if (principal.role !in setOf(secdrill.kernel.OperatorRole.OPERATOR, secdrill.kernel.OperatorRole.SECURITY_ADMIN)) {
            throw secdrill.kernel.ApiException(secdrill.kernel.ErrorCode.FORBIDDEN, "Role is not allowed to quarantine Runners")
        }
    }
}
