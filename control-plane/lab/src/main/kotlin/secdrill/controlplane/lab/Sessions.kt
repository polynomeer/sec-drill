package secdrill.controlplane.lab

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.platform.IdempotencyDecision
import secdrill.controlplane.platform.IdempotencyStore
import secdrill.controlplane.platform.OutboxWriter
import secdrill.kernel.ApiException
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.EventType
import secdrill.kernel.EvidenceSource
import secdrill.kernel.FieldError
import secdrill.kernel.LabTerminateReason
import secdrill.kernel.Mode
import secdrill.kernel.Phase
import secdrill.kernel.SessionStatus
import secdrill.kernel.TrustLevel
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Session creation, view and finish (13, 15). A Session pins one PUBLISHED scenario version and its rubric, engine
 * and randomization versions at creation; later publications never change it.
 */
@Service
class SessionService(
    private val jdbc: JdbcClient,
    private val idempotency: IdempotencyStore,
    private val guard: OwnershipGuard,
    private val ledger: LedgerAppender,
    private val outbox: OutboxWriter,
    private val labs: LabService,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val random = SecureRandom()

    @Transactional
    fun create(principal: LearnerPrincipal, key: UUID, versionId: UUID, mode: Mode, parentId: UUID?, requestDigest: String): Pair<Int, String> {
        val owner = principal.userId.value
        val route = "POST /v1/sessions"
        when (val decision = idempotency.begin(owner, route, key, requestDigest)) {
            is IdempotencyDecision.Replay -> return decision.status to decision.body
            IdempotencyDecision.Conflict -> throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key was used with a different request")
            IdempotencyDecision.Proceed -> Unit
        }
        // Unpublished and quarantined versions look absent (15).
        val manifest = jdbc.sql("SELECT public_manifest::text FROM scenario_versions WHERE id = ? AND status = 'PUBLISHED'").param(versionId)
            .query(String::class.java).optional().orElseThrow { ApiException(ErrorCode.NOT_FOUND, "Resource not found") }
            .let { json.readTree(it) }
        if (manifest["modes"]?.values()?.none { it.asString() == mode.name } != false) {
            throw ApiException(ErrorCode.UNSUPPORTED_MODE, "This scenario does not offer the requested mode")
        }
        parentId?.let { guard.requireOwned(principal, OwnedResource.SESSION, it) }
        val versions = manifest["versions"]
        val phase = manifest["phases"]?.values()?.firstOrNull()?.asString()?.let { name -> Phase.entries.firstOrNull { it.name == name } } ?: Phase.ANALYZE
        val sessionId = UUID.randomUUID()
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
        jdbc.sql(
            """INSERT INTO sessions(id, owner_id, scenario_version_id, parent_session_id, mode, status, phase, seed, rubric_version, engine_version,
               randomization_version, created_at) VALUES (?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?, ?, ?)""",
        ).params(
            listOf(sessionId, owner, versionId, parentId, mode.name, phase.name, ByteArray(32).also(random::nextBytes),
                versions?.get("rubric")?.asString() ?: "unversioned", versions?.get("engine")?.asString() ?: "unversioned",
                versions?.get("randomization")?.asString() ?: "unversioned", now),
        ).update()
        val evidence = ledger.append(sessionId, EventType.SessionCreated.name, EvidenceSource.CONTROL, TrustLevel.SERVER_VERIFIED,
            mapOf("sessionId" to sessionId, "scenarioVersionId" to versionId, "mode" to mode.name))
        outbox.append(EventType.SessionCreated, sessionId, 0, sessionId, sessionId,
            mapOf("sessionId" to sessionId.toString(), "scenarioVersionId" to versionId.toString(), "mode" to mode.name), seq = evidence.seq)
        val body = json.writeValueAsString(labs.sessionView(sessionId))
        idempotency.record(owner, route, key, requestDigest, 201, body)
        return 201 to body
    }

    fun view(principal: LearnerPrincipal, sessionId: UUID): SessionView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        return labs.sessionView(sessionId)
    }

    /**
     * Finish (13): only when the mode's completion requirements hold. Moves to SUBMITTED and terminates the Lab.
     * The final report job (EVALUATING → COMPLETED) is not implemented yet (T12), so the Session stays SUBMITTED.
     */
    @Transactional
    fun finish(principal: LearnerPrincipal, sessionId: UUID, expectedVersion: Long): SessionView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val session = jdbc.sql(
            """SELECT s.status, s.version, s.mode, s.scenario_version_id, sv.public_manifest::text FROM sessions s
               JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.id = ? FOR UPDATE OF s""",
        ).param(sessionId).query { rs, _ -> FinishRow(SessionStatus.valueOf(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getObject(4, UUID::class.java), rs.getString(5)) }.single()
        if (session.status != SessionStatus.ACTIVE) throw ApiException(ErrorCode.INVALID_STATE, "Only an active Session can be finished")
        if (session.version != expectedVersion) throw ApiException(ErrorCode.VERSION_CONFLICT, "Session version changed", ErrorDetails(latestVersion = session.version))
        val required = json.readTree(session.manifest)["completionRequirements"]?.get(session.mode)?.values()?.map { it.asString() } ?: listOf("objective_confirmed")
        val passing = passingEvaluations(sessionId, session.versionId)
        val missing = required.filterNot { it == "objective_confirmed" && passing != null }
        if (missing.isNotEmpty()) throw ApiException(ErrorCode.MISSING_GATES, "Completion requirements are not met", ErrorDetails(missingGates = missing))

        jdbc.sql("UPDATE sessions SET status = 'SUBMITTED', version = version + 1 WHERE id = ?").param(sessionId).update()
        jdbc.sql("SELECT id FROM labs WHERE session_id = ? AND cleanup_confirmed_at IS NULL").param(sessionId).query(UUID::class.java).list().filterNotNull()
            .forEach { labs.requestTermination(it, LabTerminateReason.USER_STOP) }
        val refs = passing.orEmpty().map(UUID::toString)
        val evidence = ledger.append(sessionId, EventType.SessionFinished.name, EvidenceSource.CONTROL, TrustLevel.SERVER_VERIFIED,
            mapOf("sessionId" to sessionId, "evaluationRefs" to refs))
        outbox.append(EventType.SessionFinished, sessionId, session.version + 1, sessionId, sessionId,
            mapOf("sessionId" to sessionId.toString(), "evaluationRefs" to refs), seq = evidence.seq)
        return labs.sessionView(sessionId)
    }

    /** Active PASS evaluations confirming every challenge of the version, or null if any challenge is unconfirmed. */
    private fun passingEvaluations(sessionId: UUID, versionId: UUID): List<UUID>? {
        val challenges = jdbc.sql("SELECT id FROM challenges WHERE version_id = ?").param(versionId).query(UUID::class.java).list().filterNotNull()
        if (challenges.isEmpty()) return null
        val confirmed = challenges.map { challenge ->
            jdbc.sql(
                """SELECT e.id FROM evaluations e JOIN submissions s ON s.id = e.submission_id
                   WHERE s.session_id = ? AND s.safe_metadata->>'challengeId' = ? AND e.is_active AND e.verdict = 'PASS' ORDER BY e.created_at LIMIT 1""",
            ).params(sessionId, challenge.toString()).query(UUID::class.java).optional().orElse(null) ?: return null
        }
        return confirmed
    }

    private data class FinishRow(val status: SessionStatus, val version: Long, val mode: String, val versionId: UUID, val manifest: String)
}

/** `POST /v1/sessions`, `GET /v1/sessions/{id}`, `POST /v1/sessions/{id}/finish` (15). */
@RestController
class SessionController(private val sessions: SessionService) {
    @PostMapping("/v1/sessions")
    fun create(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<String> {
        val key = idempotencyKey?.let { runCatching { Uuids.parse(it) }.getOrNull() }
            ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key header must be a UUID")
        val errors = mutableListOf<FieldError>()
        if (!body.isObject) throw ApiException(ErrorCode.VALIDATION_FAILED, "Session request is invalid", ErrorDetails(fieldErrors = listOf(FieldError("$", "must be an object"))))
        (body.propertyNames().toSet() - setOf("scenarioVersionId", "mode", "parentSessionId")).forEach { errors += FieldError(it, "unknown field") }
        fun uuid(field: String): UUID? = body[field]?.takeIf { it.isString }?.asString()?.let { runCatching { Uuids.parse(it) }.getOrNull() }
        val version = uuid("scenarioVersionId").also { if (it == null) errors += FieldError("scenarioVersionId", "must be a UUID") }
        val mode = body["mode"]?.takeIf { it.isString }?.asString()?.let { name -> Mode.entries.firstOrNull { it.name == name } }
            .also { if (it == null) errors += FieldError("mode", "must be one of ${Mode.entries.joinToString()}") }
        val parent = if (body.has("parentSessionId")) uuid("parentSessionId").also { if (it == null) errors += FieldError("parentSessionId", "must be a UUID") } else null
        if (errors.isNotEmpty()) throw ApiException(ErrorCode.VALIDATION_FAILED, "Session request is invalid", ErrorDetails(fieldErrors = errors))
        val digest = Digests.canonical(mapOf("scenarioVersionId" to version.toString(), "mode" to mode!!.name, "parentSessionId" to parent?.toString()))
        val (status, response) = sessions.create(principal, key, version!!, mode, parent, digest)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(response)
    }

    @GetMapping("/v1/sessions/{id}")
    fun get(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID) = sessions.view(principal, id)

    @PostMapping("/v1/sessions/{id}/finish")
    fun finish(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @RequestBody body: JsonNode): ResponseEntity<SessionView> {
        val version = body["expectedVersion"]?.takeIf { it.isIntegralNumber && it.asLong() >= 0 }?.asLong()
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "expectedVersion must be a non-negative integer")
        return ResponseEntity.accepted().body(sessions.finish(principal, id, version))
    }
}
