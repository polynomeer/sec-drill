package secdrill.controlplane.submission

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.ctf.CtfProperties
import secdrill.controlplane.ctf.FlagBinding
import secdrill.controlplane.ctf.FlagService
import secdrill.controlplane.evidence.ArtifactService
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.platform.FaultPoints
import secdrill.controlplane.platform.IdempotencyDecision
import secdrill.controlplane.platform.IdempotencyStore
import secdrill.controlplane.platform.OutboxWriter
import secdrill.kernel.ApiException
import secdrill.kernel.ArtifactSensitivity
import secdrill.kernel.FieldError
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

/** OpenAPI `Submission` response shape. `evaluation` is the active revision once one exists. */
data class SubmissionView(
    val id: UUID, val sessionId: UUID, val kind: SubmissionKind, val status: SubmissionStatus, val createdAt: String,
    val evaluation: EvaluationView? = null,
)

data class GateView(val key: String, val result: String)
data class DimensionView(val key: String, val score: Int, val evidenceIds: List<UUID>)

/** OpenAPI `Evaluation`. `demo` is true for fake workers and any runtime without verified isolation (prompt 08). */
data class EvaluationView(
    val id: UUID, val revision: Int, val policyVersion: String, val verdict: String,
    val patchGate: String?,
    val dimensions: List<DimensionView>, val gates: List<GateView>, val demo: Boolean,
)

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
    private val flags: FlagService,
    private val ctf: CtfProperties,
    private val artifacts: ArtifactService,
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
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        request.flag?.let { requireFlagAllowed(sessionId, it, now) }
        val sessionVersion = casSession(sessionId, owner, request.expectedVersion)

        val submissionId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        // FLAG (15, 09): check the HMAC in memory, keep a signed receipt in the private store, drop the raw flag.
        val flagCheck = request.flag?.let { checkFlag(sessionId, submissionId, jobId, it, now) }
        jdbc.sql(
            """INSERT INTO submissions(id, session_id, kind, client_request_id, request_digest, status, safe_metadata, created_at, artifact_id)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
        ).params(
            listOf(submissionId, sessionId, request.kind.name, idempotencyKey, request.digest, SubmissionStatus.ACCEPTED.name,
                json.writeValueAsString(flagCheck?.metadata ?: emptyMap<String, Any>()), now.atOffset(ZoneOffset.UTC), flagCheck?.receiptId),
        ).update()
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

    private class FlagCheck(val receiptId: UUID, val metadata: Map<String, Any?>)

    /** The challenge must be a FLAG challenge of the pinned version; wrong flags are limited per minute (09). */
    private fun requireFlagAllowed(sessionId: UUID, flag: SubmissionRequest.FlagContent, now: java.time.Instant) {
        val manifest = json.readTree(
            jdbc.sql("SELECT sv.public_manifest::text FROM sessions s JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.id = ?")
                .param(sessionId).query(String::class.java).single(),
        )
        val known = manifest["challenges"]?.values()?.any { it["id"]?.asString() == flag.challengeId.toString() && it["kind"]?.asString() == "FLAG" } == true
        if (!known) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Submission is invalid", ErrorDetails(fieldErrors = listOf(FieldError("content.challengeId", "is not a flag challenge of this scenario"))))
        }
        val recentWrong = jdbc.sql(
            "SELECT count(*) FROM submissions WHERE session_id = ? AND kind = 'FLAG' AND safe_metadata->>'flagMatched' = 'false' AND created_at > ?",
        ).params(sessionId, now.minusSeconds(60).atOffset(ZoneOffset.UTC)).query(Int::class.java).single()
        if (recentWrong >= ctf.wrongFlagsPerMinute) {
            throw ApiException(ErrorCode.RATE_LIMITED, "Too many incorrect flags; wait before trying again", retryAfterSeconds = 60)
        }
    }

    private fun checkFlag(sessionId: UUID, submissionId: UUID, jobId: UUID, flag: SubmissionRequest.FlagContent, now: java.time.Instant): FlagCheck {
        // Only live Lab generations count: flags of replaced or terminated Labs are refused (09).
        val bindings = jdbc.sql(
            "SELECT id, generation, flag_nonce, flag_key_version FROM labs WHERE session_id = ? AND desired_state = 'RUNNING' AND flag_nonce IS NOT NULL",
        ).param(sessionId).query { rs, _ -> FlagBinding(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getBytes(3), rs.getString(4)) }.list()
        val match = flags.match(flag.value, sessionId, flag.challengeId, bindings)
        val keyVersion = flags.activeKeyVersion
        val body = mapOf(
            "v" to 1, "submissionId" to submissionId.toString(), "jobId" to jobId.toString(), "sessionId" to sessionId.toString(),
            "challengeId" to flag.challengeId.toString(), "matched" to (match != null), "labId" to match?.labId?.toString(),
            "generation" to match?.generation, "keyVersion" to keyVersion, "checkedAt" to Rfc3339.format(now),
        ).filterValues { it != null } // absent rather than null, so the stored JSON and the signed form agree
        val canonical = secdrill.kernel.CanonicalJson.encode(body)
        val receipt = json.writeValueAsBytes(mapOf("body" to body, "signature" to flags.signReceipt(canonical, keyVersion)))
        val stored = artifacts.store(sessionId, ArtifactSensitivity.PRIVATE_ORACLE, "application/json", receipt)
        val metadata = mapOf("challengeId" to flag.challengeId.toString(), "flagMatched" to (match != null), "labId" to match?.labId?.toString())
        return FlagCheck(stored.id, metadata.filterValues { it != null })
    }

    /** `GET /v1/submissions/{id}`: owner only (404 otherwise), with the active evaluation. */
    fun view(principal: LearnerPrincipal, submissionId: UUID): SubmissionView {
        guard.requireOwned(principal, OwnedResource.SUBMISSION, submissionId)
        val base = jdbc.sql("SELECT session_id, kind, status, created_at FROM submissions WHERE id = ?").param(submissionId).query { rs, _ ->
            SubmissionView(submissionId, rs.getObject(1, UUID::class.java), SubmissionKind.valueOf(rs.getString(2)), SubmissionStatus.valueOf(rs.getString(3)),
                Rfc3339.format(rs.getObject(4, java.time.OffsetDateTime::class.java).toInstant()))
        }.single()
        val isolationVerified = jdbc.sql("SELECT coalesce(bool_and(isolation_verified), false) FROM labs WHERE session_id = ?")
            .param(base.sessionId).query(Boolean::class.java).single()
        val evaluation = jdbc.sql(
            "SELECT id, revision, policy_version, verdict, patch_gate, gates::text, demo FROM evaluations WHERE submission_id = ? AND is_active",
        ).param(submissionId).query { rs, _ ->
            val gates = json.readTree(rs.getString(6)).values().map { GateView(it["key"].asString(), it["result"].asString()) }
            EvaluationView(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), emptyList(), gates,
                rs.getBoolean(7) || !isolationVerified)
        }.optional().orElse(null)
        return base.copy(evaluation = evaluation)
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
