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
import tools.jackson.databind.JsonNode
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
data class DimensionView(val key: String, val score: Double, val evidenceIds: List<UUID>)

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
    private val grading: GradingProperties,
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
        request.patch?.let { requirePatchAllowed(sessionId, it) }
        request.detection?.let { requireDetectionAllowed(sessionId) }
        request.objectiveChallengeId?.let { requireObjectiveAllowed(sessionId, it) }
        val sessionVersion = casSession(sessionId, owner, request.expectedVersion)

        val submissionId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        // FLAG (15, 09): check the HMAC in memory, keep a signed receipt in the private store, drop the raw flag.
        val checked = request.flag?.let { checkFlag(sessionId, submissionId, jobId, it, now) }
            ?: request.patch?.let { storePatch(sessionId, it) }
            ?: request.detection?.let { storeDetection(sessionId, it) }
            ?: request.recorded?.let { storeRecorded(sessionId, submissionId, request.kind, it, request.objectiveChallengeId) }
        jdbc.sql(
            """INSERT INTO submissions(id, session_id, kind, client_request_id, request_digest, status, safe_metadata, created_at, artifact_id)
               VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
        ).params(
            listOf(submissionId, sessionId, request.kind.name, idempotencyKey, request.digest, SubmissionStatus.ACCEPTED.name,
                json.writeValueAsString(checked?.metadata ?: emptyMap<String, Any>()), now.atOffset(ZoneOffset.UTC), checked?.artifactId),
        ).update()
        // Kinds with a grader get a GRADE job. OBJECTIVE is verified by independent observation only when a live Lab
        // exists (requireObjectiveAllowed, 09, ADR 0014); POSTMORTEM is recorded for human review and gets no job,
        // which would otherwise leave a job no grader ever claims.
        val graded = request.kind in setOf(SubmissionKind.FLAG, SubmissionKind.PATCH, SubmissionKind.DETECTION, SubmissionKind.OBJECTIVE)
        if (graded) {
            jdbc.sql("INSERT INTO jobs(id, submission_id, session_id, kind, state, due_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(jobId, submissionId, sessionId, JobKind.GRADE.name, JobState.PENDING.name, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC))
                .update()
        }
        val evidence = ledger.append(
            sessionId, EventType.SubmissionAccepted.name, EvidenceSource.CONTROL, TrustLevel.SERVER_VERIFIED,
            mapOf("submissionId" to submissionId, "jobId" to (if (graded) jobId else null), "kind" to request.kind.name, "requestDigest" to request.digest),
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
                "jobId" to (if (graded) jobId.toString() else null),
                "kind" to request.kind.name,
                // Content storage arrives with T09; until then the bundle is identified by its request digest only.
                "bundleRef" to (checked?.bundleRef ?: mapOf("key" to "submission-request/$submissionId", "digest" to request.digest, "byteSize" to request.byteSize)),
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

    private class ContentCheck(val artifactId: UUID, val metadata: Map<String, Any?>, val bundleRef: Map<String, Any>? = null)

    /**
     * PATCH (08, 20): only the pinned manifest's allowed paths, a mode with a patch phase, and a grading runtime that
     * is verified or explicitly allowed for development (fail closed otherwise).
     */
    private fun requirePatchAllowed(sessionId: UUID, patch: SubmissionRequest.PatchContent) {
        val (mode, manifestText) = jdbc.sql("SELECT s.mode, sv.public_manifest::text FROM sessions s JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.id = ?")
            .param(sessionId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        val spec = json.readTree(manifestText)["patch"]
        if (spec == null || mode !in setOf("PATCH", "PURPLE")) throw ApiException(ErrorCode.UNSUPPORTED_MODE, "This Session does not take patches")
        val allowed = spec["allowedPaths"]?.values()?.map { it.asString() }?.toSet().orEmpty()
        val maxFiles = spec["maxFiles"]?.asInt() ?: 100
        val errors = mutableListOf<FieldError>()
        if (patch.files.keys.any { it !in allowed }) errors += FieldError("content.files", "only the scenario's allowed paths may be changed")
        if (patch.files.size > maxFiles) errors += FieldError("content.files", "at most $maxFiles files")
        val expanded = patch.files.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        if (expanded > (spec["maxExpandedBytes"]?.asLong() ?: Long.MAX_VALUE)) errors += FieldError("content.files", "files are too large")
        if (errors.isNotEmpty()) throw ApiException(ErrorCode.VALIDATION_FAILED, "Submission is invalid", ErrorDetails(fieldErrors = errors))
        if (!grading.isolationVerified && !grading.allowUnverifiedIsolation) {
            throw ApiException(ErrorCode.SERVICE_UNAVAILABLE, "No grading runtime with the required isolation is available")
        }
    }

    private fun requireDetectionAllowed(sessionId: UUID) {
        val mode = jdbc.sql("SELECT mode FROM sessions WHERE id = ?").param(sessionId).query(String::class.java).single()
        if (mode !in setOf("PURPLE", "DETECTION")) throw ApiException(ErrorCode.UNSUPPORTED_MODE, "This Session has no detection stage")
    }

    /** Stores the rule and explanation as the learner's artifact; the explanation is the learner's claim (USER_REPORTED). */
    private fun storeDetection(sessionId: UUID, detection: SubmissionRequest.DetectionContent): ContentCheck {
        val ruleDigest = secdrill.kernel.Digests.canonical(json.convertValue(detection.ruleJson, Map::class.java))
        val bytes = json.writeValueAsBytes(mapOf("ruleDigest" to ruleDigest, "rule" to detection.ruleJson, "explanation" to detection.explanation))
        val stored = artifacts.store(sessionId, ArtifactSensitivity.LEARNER, "application/json", bytes)
        ledger.append(sessionId, "HYPOTHESIS_REPORTED", EvidenceSource.USER, TrustLevel.USER_REPORTED, mapOf(
            "ruleDigest" to ruleDigest, "explanationDigest" to secdrill.kernel.Digests.sha256Hex(detection.explanation.toByteArray()),
        ), artifactId = stored.id)
        return ContentCheck(stored.id, mapOf("ruleDigest" to ruleDigest), mapOf("key" to stored.key, "digest" to stored.digest, "byteSize" to stored.byteSize))
    }

    /**
     * Records OBJECTIVE/POSTMORTEM content as the learner's artifact and USER_REPORTED evidence (10). The
     * explanation is the learner's claim and is never auto-graded; for OBJECTIVE the verdict comes from independent
     * observation of [challengeId] (ADR 0014), which is kept in the metadata so the grader knows what to observe.
     */
    private fun storeRecorded(sessionId: UUID, submissionId: UUID, kind: SubmissionKind, content: JsonNode, challengeId: UUID?): ContentCheck {
        val bytes = json.writeValueAsBytes(mapOf("kind" to kind.name, "content" to content))
        val stored = artifacts.store(sessionId, ArtifactSensitivity.LEARNER, "application/json", bytes)
        val eventType = if (kind == SubmissionKind.OBJECTIVE) "HYPOTHESIS_REPORTED" else "POSTMORTEM_SUBMITTED"
        ledger.append(sessionId, eventType, EvidenceSource.USER, TrustLevel.USER_REPORTED,
            mapOf("submissionId" to submissionId, "contentDigest" to secdrill.kernel.Digests.sha256Hex(bytes)), artifactId = stored.id)
        val metadata = if (challengeId != null) mapOf("challengeId" to challengeId.toString()) else mapOf("recorded" to true)
        return ContentCheck(stored.id, metadata, mapOf("key" to stored.key, "digest" to stored.digest, "byteSize" to stored.byteSize))
    }

    /**
     * OBJECTIVE (09, ADR 0014): a WARGAME/PURPLE Session, an OBJECTIVE challenge of the pinned manifest, and a live
     * Lab to observe. Without a Lab there is nothing to verify, so the submission is refused rather than left as a
     * grade job no runner can satisfy.
     */
    private fun requireObjectiveAllowed(sessionId: UUID, challengeId: UUID) {
        val (mode, manifestText) = jdbc.sql("SELECT s.mode, sv.public_manifest::text FROM sessions s JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.id = ?")
            .param(sessionId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        if (mode !in setOf("WARGAME", "PURPLE")) throw ApiException(ErrorCode.UNSUPPORTED_MODE, "This Session has no Wargame objective stage")
        val known = json.readTree(manifestText)["challenges"]?.values()?.any { it["id"]?.asString() == challengeId.toString() && it["kind"]?.asString() == "OBJECTIVE" } == true
        if (!known) throw ApiException(ErrorCode.VALIDATION_FAILED, "Submission is invalid", ErrorDetails(fieldErrors = listOf(FieldError("content.challengeId", "is not an objective challenge of this scenario"))))
        val liveLab = jdbc.sql("SELECT count(*) FROM labs WHERE session_id = ? AND desired_state = 'RUNNING' AND cleanup_confirmed_at IS NULL").param(sessionId).query(Int::class.java).single() > 0
        if (!liveLab) throw ApiException(ErrorCode.INVALID_STATE, "A live Lab is needed to verify the objective")
    }

    /** Canonical bundle (20): files by path with their SHA-256, plus the explanation digest; stored as the learner's artifact. */
    private fun storePatch(sessionId: UUID, patch: SubmissionRequest.PatchContent): ContentCheck {
        val manifest = patch.files.toSortedMap().map { (path, text) ->
            val bytes = text.toByteArray(Charsets.UTF_8)
            mapOf("path" to path, "sha256" to secdrill.kernel.Digests.sha256Hex(bytes), "byteSize" to bytes.size)
        }
        val bundleDigest = secdrill.kernel.Digests.canonical(mapOf("files" to manifest, "explanationDigest" to secdrill.kernel.Digests.sha256Hex(patch.explanation.toByteArray())))
        val bytes = json.writeValueAsBytes(mapOf("bundleDigest" to bundleDigest, "files" to patch.files.toSortedMap(), "explanation" to patch.explanation))
        val stored = artifacts.store(sessionId, ArtifactSensitivity.LEARNER, "application/json", bytes)
        return ContentCheck(stored.id, mapOf("bundleDigest" to bundleDigest, "fileCount" to patch.files.size),
            mapOf("key" to stored.key, "digest" to stored.digest, "byteSize" to stored.byteSize))
    }

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

    private fun checkFlag(sessionId: UUID, submissionId: UUID, jobId: UUID, flag: SubmissionRequest.FlagContent, now: java.time.Instant): ContentCheck {
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
        return ContentCheck(stored.id, metadata.filterValues { it != null })
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
            "SELECT id, revision, policy_version, verdict, patch_gate, gates::text, demo, dimensions::text FROM evaluations WHERE submission_id = ? AND is_active",
        ).param(submissionId).query { rs, _ ->
            val gates = json.readTree(rs.getString(6)).values().map { GateView(it["key"].asString(), it["result"].asString()) }
            val dimensions = json.readTree(rs.getString(8)).values().map { DimensionView(it["key"].asString(), it["scoreBps"].asInt() / 100.0, emptyList()) }
            EvaluationView(rs.getObject(1, UUID::class.java), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), dimensions, gates,
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
