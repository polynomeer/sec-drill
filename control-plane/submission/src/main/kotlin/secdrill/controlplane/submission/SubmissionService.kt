package secdrill.controlplane.submission

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.platform.FaultPoints
import secdrill.controlplane.platform.IdempotencyDecision
import secdrill.controlplane.platform.IdempotencyStore
import secdrill.controlplane.platform.OutboxWriter
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.EventType
import secdrill.kernel.EvidenceSource
import secdrill.kernel.JobKind
import secdrill.kernel.JobState
import secdrill.kernel.Rfc3339
import secdrill.kernel.SessionStatus
import secdrill.kernel.SubmissionKind
import secdrill.kernel.SubmissionStatus
import secdrill.kernel.TrustLevel
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** OpenAPI `Submission` response shape. */
data class SubmissionView(val id: UUID, val sessionId: UUID, val kind: SubmissionKind, val status: SubmissionStatus, val createdAt: String)

data class HttpReply(val status: Int, val body: String)

/**
 * Accepts a submission in one transaction (14, 16): idempotency check, owner guard, Session version CAS,
 * Submission, GRADE job, Evidence and Outbox event, and the replayable response. Any failure before commit leaves
 * none of them. The raw content is not stored; only its canonical digest is (15: raw flags are never persisted).
 */
@Service
class SubmissionService(
    private val jdbc: JdbcClient,
    private val idempotency: IdempotencyStore,
    private val guard: OwnershipGuard,
    private val ledger: LedgerAppender,
    private val outbox: OutboxWriter,
    private val faults: FaultPoints,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    companion object {
        const val FAULT_BEFORE_COMMIT = "submission.accept.before-commit"
    }

    @Transactional
    fun accept(principal: LearnerPrincipal, sessionId: UUID, idempotencyKey: UUID, request: SubmissionRequest): HttpReply {
        val owner = principal.userId.value
        val route = "POST /v1/sessions/$sessionId/submissions"
        // Replaying the original response takes precedence over the version check (15).
        when (val decision = idempotency.begin(owner, route, idempotencyKey, request.digest)) {
            is IdempotencyDecision.Replay -> return HttpReply(decision.status, decision.body)
            IdempotencyDecision.Conflict -> throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key was used with a different request")
            IdempotencyDecision.Proceed -> Unit
        }
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val sessionVersion = casSession(sessionId, owner, request.expectedVersion)

        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val submissionId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO submissions(id, session_id, kind, client_request_id, request_digest, status, safe_metadata, created_at)
               VALUES (?, ?, ?, ?, ?, ?, '{}', ?)""",
        ).params(submissionId, sessionId, request.kind.name, idempotencyKey, request.digest, SubmissionStatus.ACCEPTED.name, now.atOffset(ZoneOffset.UTC))
            .update()
        jdbc.sql("INSERT INTO jobs(id, submission_id, session_id, kind, state, due_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
            .params(jobId, submissionId, sessionId, JobKind.GRADE.name, JobState.PENDING.name, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC))
            .update()
        val evidence = ledger.append(
            sessionId, EventType.SubmissionAccepted.name, EvidenceSource.CONTROL, TrustLevel.SERVER_VERIFIED,
            mapOf("submissionId" to submissionId, "jobId" to jobId, "kind" to request.kind.name, "requestDigest" to request.digest),
        )
        outbox.append(
            type = EventType.SubmissionAccepted,
            aggregateId = submissionId,
            aggregateVersion = 0,
            sessionId = sessionId,
            correlationId = submissionId,
            seq = evidence.seq,
            payload = mapOf(
                "submissionId" to submissionId.toString(),
                "jobId" to jobId.toString(),
                "kind" to request.kind.name,
                // Content storage arrives with T09; until then the bundle is identified by its request digest only.
                "bundleRef" to mapOf("key" to "submission-request/$submissionId", "digest" to request.digest, "byteSize" to request.byteSize),
            ),
        )
        val reply = HttpReply(
            202,
            json.writeValueAsString(SubmissionView(submissionId, sessionId, request.kind, SubmissionStatus.ACCEPTED, Rfc3339.format(now))),
        )
        idempotency.record(owner, route, idempotencyKey, request.digest, reply.status, reply.body)
        faults.reach(FAULT_BEFORE_COMMIT)
        return reply
    }

    /** Session version CAS (13). Belongs to the Session module once it exists (T04). */
    private fun casSession(sessionId: UUID, owner: UUID, expectedVersion: Long): Long {
        val updated = jdbc.sql(
            "UPDATE sessions SET version = version + 1 WHERE id = ? AND owner_id = ? AND version = ? AND status = ? RETURNING version",
        ).params(sessionId, owner, expectedVersion, SessionStatus.ACTIVE.name).query(Long::class.java).optional()
        if (updated.isPresent) return updated.get()
        val (status, version) = jdbc.sql("SELECT status, version FROM sessions WHERE id = ?").param(sessionId)
            .query { rs, _ -> SessionStatus.valueOf(rs.getString(1)) to rs.getLong(2) }.single()
        if (status != SessionStatus.ACTIVE) throw ApiException(ErrorCode.INVALID_STATE, "Session does not accept submissions")
        throw ApiException(ErrorCode.VERSION_CONFLICT, "Session version changed", ErrorDetails(latestVersion = version))
    }
}
