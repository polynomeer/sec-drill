package secdrill.controlplane.response

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
import secdrill.kernel.IrActionType
import secdrill.kernel.SessionStatus
import secdrill.kernel.TrustLevel
import secdrill.kernel.Uuids
import secdrill.simulation.DrillSeeds
import secdrill.simulation.IncidentModel
import secdrill.simulation.SyntheticLogs
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** OpenAPI `SystemState`: ratios as 0..1 for display; the model keeps basis points. */
data class SystemStateView(val tick: Int, val leakedSyntheticRecords: Int, val availability: Double, val workloadSuccess: Double, val evidenceCoverage: Double)

/** OpenAPI `ActionResult`. Always SIMULATED: the action changed the model, not a real Lab (21). */
data class ActionResultView(val seq: Long, val version: Long, val representation: String, val state: SystemStateView)

/**
 * Incident-response actions (15 `POST /sessions/{id}/actions`, 21, ADR 0010). The state is never stored as truth:
 * it is replayed from the Session seed, the engine version and the accepted actions, and each accepted action
 * records the resulting state digest so a replay can prove it reproduces the same state.
 */
@Service
class IncidentResponseService(
    private val jdbc: JdbcClient,
    private val idempotency: IdempotencyStore,
    private val guard: OwnershipGuard,
    private val ledger: LedgerAppender,
    private val outbox: OutboxWriter,
    private val json: JsonMapper,
) {
    private data class Drill(val status: SessionStatus, val version: Long, val mode: String, val seed: ByteArray, val engine: String, val manifest: JsonNode)

    private fun drill(sessionId: UUID, lock: Boolean): Drill = jdbc.sql(
        """SELECT s.status, s.version, s.mode, s.seed, s.engine_version, sv.public_manifest::text FROM sessions s
           JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.id = ?""" + if (lock) " FOR UPDATE OF s" else "",
    ).param(sessionId).query { rs, _ ->
        Drill(SessionStatus.valueOf(rs.getString(1)), rs.getLong(2), rs.getString(3), rs.getBytes(4), rs.getString(5), json.readTree(rs.getString(6)))
    }.single()

    private fun actions(sessionId: UUID): List<IncidentModel.Action> =
        jdbc.sql("SELECT action_type, target FROM applied_actions WHERE session_id = ? ORDER BY seq").param(sessionId)
            .query { rs, _ -> IncidentModel.Action(IrActionType.valueOf(rs.getString(1)), rs.getString(2)) }.list()

    /** The engine for this Session; a Session pinned to an engine this build does not know stays read-only (22). */
    private fun engineOf(drill: Drill): String = if (drill.engine == IncidentModel.ENGINE) drill.engine
        else throw ApiException(ErrorCode.INVALID_STATE, "This Session's response model version is not supported for new actions")

    @Transactional
    fun apply(principal: LearnerPrincipal, sessionId: UUID, key: UUID, action: IncidentModel.Action, expectedVersion: Long, requestDigest: String): Pair<Int, String> {
        val owner = principal.userId.value
        val route = "POST /v1/sessions/$sessionId/actions"
        when (val decision = idempotency.begin(owner, route, key, requestDigest)) {
            is IdempotencyDecision.Replay -> return decision.status to decision.body
            IdempotencyDecision.Conflict -> throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key was used with a different request")
            IdempotencyDecision.Proceed -> Unit
        }
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val drill = drill(sessionId, lock = true)
        if (drill.mode != "PURPLE") throw ApiException(ErrorCode.UNSUPPORTED_MODE, "Response actions are part of the Purple mode")
        if (drill.manifest["actions"]?.values()?.none { it.asString() == action.type.name } != false) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Action is invalid", ErrorDetails(fieldErrors = listOf(FieldError("type", "is not offered by this scenario"))))
        }
        if (drill.status != SessionStatus.ACTIVE) throw ApiException(ErrorCode.INVALID_STATE, "Session does not accept actions")
        if (drill.version != expectedVersion) throw ApiException(ErrorCode.VERSION_CONFLICT, "Session version changed", ErrorDetails(latestVersion = drill.version))
        val engine = engineOf(drill)
        val seed = DrillSeeds.of(drill.seed, DrillSeeds.INCIDENT)
        val previous = actions(sessionId)
        val before = IncidentModel.replay(seed, previous, engine)
        val transition = try {
            IncidentModel.next(before, action, seed)
        } catch (error: IncidentModel.InvalidAction) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Action is invalid", ErrorDetails(fieldErrors = listOf(FieldError("parameters", "names nothing in this scenario"))))
        }
        // A conflicting or repeated action changes nothing; it is refused rather than spending a tick (21).
        transition.noEffect?.let { throw ApiException(ErrorCode.INVALID_STATE, "Action has no effect: ${it.name.lowercase().replace('_', ' ')}") }

        val state = transition.state
        val actionId = UUID.randomUUID()
        val digest = state.digest()
        jdbc.sql(
            "INSERT INTO applied_actions(id, session_id, seq, action_type, target, tick, engine_version, state_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ).params(actionId, sessionId, previous.size + 1, action.type.name, action.target, state.tick, engine, digest).update()
        if (secdrill.simulation.Checkpoints.isCheckpoint(state.tick)) {
            jdbc.sql("INSERT INTO ir_checkpoints(session_id, tick, engine_version, state, state_digest) VALUES (?, ?, ?, ?::jsonb, ?)")
                .params(sessionId, state.tick, engine, json.writeValueAsString(secdrill.simulation.Checkpoints.toMap(state)), digest).update()
        }
        val version = jdbc.sql("UPDATE sessions SET version = version + 1 WHERE id = ? RETURNING version").param(sessionId).query(Long::class.java).single()
        val evidence = ledger.append(sessionId, EventType.ActionApplied.name, EvidenceSource.SIMULATOR, TrustLevel.SIMULATED, mapOf(
            "actionId" to actionId, "type" to action.type.name, "tick" to state.tick, "stateDigest" to digest, "engineVersion" to engine,
            "leakedThisTick" to transition.leakedThisTick, "failedNormalRequests" to transition.failedThisTick,
        ))
        outbox.append(EventType.ActionApplied, actionId, 0, sessionId, sessionId,
            mapOf("actionId" to actionId.toString(), "tick" to state.tick, "stateDigest" to digest), seq = evidence.seq)
        val body = json.writeValueAsString(ActionResultView(evidence.seq, version, "SIMULATED", view(state)))
        idempotency.record(owner, route, key, requestDigest, 200, body)
        return 200 to body
    }

    /** Recomputes the state from (seed, engine, actions) and checks it against every recorded digest (22). */
    fun verifyReplay(sessionId: UUID): Boolean {
        val drill = drill(sessionId, lock = false)
        val seed = DrillSeeds.of(drill.seed, DrillSeeds.INCIDENT)
        val recorded = jdbc.sql("SELECT action_type, target, state_digest FROM applied_actions WHERE session_id = ? ORDER BY seq").param(sessionId)
            .query { rs, _ -> IncidentModel.Action(IrActionType.valueOf(rs.getString(1)), rs.getString(2)) to rs.getString(3) }.list()
        var state = IncidentModel.initial(seed, drill.engine)
        return recorded.all { (action, digest) -> state = IncidentModel.next(state, action, seed).state; state.digest() == digest }
    }

    fun trainingDataset(principal: LearnerPrincipal, sessionId: UUID): Map<String, Any> {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val drill = drill(sessionId, lock = false)
        if (drill.mode !in setOf("PURPLE", "DETECTION")) throw ApiException(ErrorCode.UNSUPPORTED_MODE, "This Session has no detection stage")
        // Training only. The holdout comes from another derived seed and is never served.
        val data = SyntheticLogs.generate(DrillSeeds.of(drill.seed, DrillSeeds.TRAINING), SyntheticLogs.Variant.TRAINING)
        return mapOf("variant" to "TRAINING", "generator" to SyntheticLogs.VERSION, "representation" to "SIMULATED", "events" to data.events)
    }

    private fun view(state: IncidentModel.State) = SystemStateView(
        state.tick, state.leakedSyntheticRecords, state.availabilityBps / 10_000.0, state.workloadSuccessBps / 10_000.0, state.evidenceCoverageBps / 10_000.0,
    )
}

@RestController
class IncidentResponseController(private val service: IncidentResponseService) {
    private val targets = mapOf(
        IrActionType.REVOKE_TOKEN to "tokenId", IrActionType.DISABLE_ENDPOINT to "routeGroup",
        IrActionType.ISOLATE_WORKLOAD to "workloadId", IrActionType.ENABLE_AUDIT to "source",
    )

    @PostMapping("/v1/sessions/{id}/actions")
    fun apply(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<String> {
        val key = idempotencyKey?.let { runCatching { Uuids.parse(it) }.getOrNull() }
            ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key header must be a UUID")
        fun invalid(field: String, message: String): Nothing = throw ApiException(ErrorCode.VALIDATION_FAILED, "Action is invalid", ErrorDetails(fieldErrors = listOf(FieldError(field, message))))
        if (!body.isObject || (body.propertyNames().toSet() - setOf("type", "parameters", "expectedVersion")).isNotEmpty()) invalid("$", "only type, parameters and expectedVersion")
        val type = IrActionType.entries.firstOrNull { it.name == body["type"]?.asString() } ?: invalid("type", "must be one of ${IrActionType.entries.joinToString()}")
        val parameter = targets.getValue(type)
        val parameters = body["parameters"]?.takeIf { it.isObject && it.propertyNames().toSet() == setOf(parameter) } ?: invalid("parameters", "must contain exactly $parameter")
        val target = parameters[parameter]?.takeIf { it.isString && it.asString().length in 1..128 }?.asString() ?: invalid("parameters.$parameter", "must be 1-128 characters")
        val version = body["expectedVersion"]?.takeIf { it.isIntegralNumber && it.asLong() >= 0 }?.asLong() ?: invalid("expectedVersion", "must be a non-negative integer")
        val digest = Digests.canonical(mapOf("type" to type.name, "target" to target, "expectedVersion" to version))
        val (status, response) = service.apply(principal, id, key, IncidentModel.Action(type, target), version, digest)
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(response)
    }

    @GetMapping("/v1/sessions/{id}/detection-dataset")
    fun dataset(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID) = service.trainingDataset(principal, id)
}
