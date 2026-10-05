package secdrill.controlplane.response

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.kernel.ApiException
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.kernel.IrActionType
import secdrill.kernel.Rfc3339
import secdrill.simulation.Checkpoints
import secdrill.simulation.DrillSeeds
import secdrill.simulation.IncidentModel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

data class ReplayChunkRef(val fromSeq: Long, val toSeq: Long, val downloadPath: String, val digest: String)
data class ReplayGap(val fromSeq: Long, val toSeq: Long, val reason: String)
data class CheckpointRef(val tick: Int, val stateDigest: String)
data class ReplayManifestView(
    val sessionId: UUID, val engineVersion: String, val firstSeq: Long, val lastSeq: Long, val lastTick: Int,
    val chunks: List<ReplayChunkRef>, val gaps: List<ReplayGap>, val checkpoints: List<CheckpointRef>,
)
data class ReplayItemView(val id: UUID, val seq: Long, val type: String, val trustLevel: String, val occurredAt: String, val summary: JsonNode, val artifact: String)
data class ReplayChunkView(val fromSeq: Long, val toSeq: Long, val digest: String, val items: List<ReplayItemView>)
data class ReplayStateView(
    val tick: Int, val representation: String, val engineVersion: String, val state: SystemStateView, val stateDigest: String,
    val fromCheckpointTick: Int, val recordedDigest: String?,
)

/**
 * Replay (22, ADR 0012). Two paths, never mixed: the ledger replays recorded facts with their trust level (observed,
 * user-reported, simulated, server-verified), and the IR model is recomputed from seed + actions with checkpoint seek.
 * Expired or deleted artifacts show as metadata only, and their seq ranges are listed as gaps.
 */
@Service
class ReplayService(private val jdbc: JdbcClient, private val guard: OwnershipGuard, private val json: JsonMapper, private val clock: Clock) {
    companion object {
        const val CHUNK = 100
    }

    private fun items(sessionId: UUID, fromSeq: Long, toSeq: Long): List<ReplayItemView> = jdbc.sql(
        """SELECT e.seq, e.event_type, e.trust_level, e.occurred_at, e.safe_payload::text, e.artifact_id, a.deleted_at, a.expires_at, e.id
           FROM evidence e LEFT JOIN artifacts a ON a.id = e.artifact_id WHERE e.session_id = ? AND e.seq BETWEEN ? AND ? ORDER BY e.seq""",
    ).params(sessionId, fromSeq, toSeq).query { rs, _ ->
        val artifact = when {
            rs.getObject(6) == null -> "NONE"
            rs.getObject(7) != null -> "DELETED"
            rs.getObject(8, OffsetDateTime::class.java)?.toInstant()?.isBefore(clock.instant()) == true -> "EXPIRED"
            else -> "AVAILABLE"
        }
        ReplayItemView(rs.getObject(9, UUID::class.java), rs.getLong(1), rs.getString(2), rs.getString(3), Rfc3339.format(rs.getObject(4, OffsetDateTime::class.java).toInstant()),
            json.readTree(rs.getString(5)), artifact)
    }.list()

    private fun digest(items: List<ReplayItemView>) = Digests.canonical(items.map { mapOf("seq" to it.seq, "type" to it.type, "trustLevel" to it.trustLevel, "artifact" to it.artifact) })

    fun manifest(principal: LearnerPrincipal, sessionId: UUID, fromSeq: Long?, toSeq: Long?): ReplayManifestView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        if (fromSeq != null && toSeq != null && fromSeq > toSeq) throw ApiException(ErrorCode.VALIDATION_FAILED, "fromSeq must not be after toSeq")
        val engine = jdbc.sql("SELECT engine_version FROM sessions WHERE id = ?").param(sessionId).query(String::class.java).single()
        val (first, last) = jdbc.sql("SELECT coalesce(min(seq), 0), coalesce(max(seq), 0) FROM evidence WHERE session_id = ?").param(sessionId)
            .query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.single()
        val from = maxOf(fromSeq ?: first, first.coerceAtLeast(1))
        val to = minOf(toSeq ?: last, last)
        val chunks = mutableListOf<ReplayChunkRef>()
        val gaps = mutableListOf<ReplayGap>()
        if (last > 0 && from <= to) {
            var start = from
            while (start <= to) {
                val end = minOf(start + CHUNK - 1, to)
                val chunk = items(sessionId, start, end)
                chunks += ReplayChunkRef(start, end, "/v1/sessions/$sessionId/replay/chunks/$start?toSeq=$end", digest(chunk))
                // A seq the ledger does not return is a gap; so is evidence whose artifact is gone.
                val present = chunk.map { it.seq }.toSet()
                (start..end).filter { it !in present }.forEach { gaps += ReplayGap(it, it, "MISSING_EVIDENCE") }
                chunk.filter { it.artifact == "EXPIRED" || it.artifact == "DELETED" }.forEach { gaps += ReplayGap(it.seq, it.seq, "ARTIFACT_${it.artifact}") }
                start = end + 1
            }
        }
        val lastTick = jdbc.sql("SELECT count(*) FROM applied_actions WHERE session_id = ?").param(sessionId).query(Int::class.java).single()
        val checkpoints = jdbc.sql("SELECT tick, state_digest FROM ir_checkpoints WHERE session_id = ? ORDER BY tick").param(sessionId)
            .query { rs, _ -> CheckpointRef(rs.getInt(1), rs.getString(2)) }.list()
        return ReplayManifestView(sessionId, engine, if (last == 0L) 0 else from, to.coerceAtLeast(0), lastTick, chunks, gaps, checkpoints)
    }

    fun chunk(principal: LearnerPrincipal, sessionId: UUID, fromSeq: Long, toSeq: Long?): ReplayChunkView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val end = toSeq ?: (fromSeq + CHUNK - 1)
        if (fromSeq < 1 || end < fromSeq || end - fromSeq >= CHUNK) throw ApiException(ErrorCode.VALIDATION_FAILED, "chunk range must be 1 to $CHUNK sequence numbers")
        val chunk = items(sessionId, fromSeq, end)
        return ReplayChunkView(fromSeq, end, digest(chunk), chunk)
    }

    /** Model state at [tick], from the nearest earlier checkpoint. Always SIMULATED. */
    fun state(principal: LearnerPrincipal, sessionId: UUID, tick: Int): ReplayStateView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val (seedBytes, engine) = jdbc.sql("SELECT seed, engine_version FROM sessions WHERE id = ?").param(sessionId).query { rs, _ -> rs.getBytes(1) to rs.getString(2) }.single()
        if (engine != IncidentModel.ENGINE) throw ApiException(ErrorCode.INVALID_STATE, "This Session's response model version is not supported for recomputation")
        val recorded = jdbc.sql("SELECT action_type, target, state_digest FROM applied_actions WHERE session_id = ? ORDER BY seq").param(sessionId)
            .query { rs, _ -> IncidentModel.Action(IrActionType.valueOf(rs.getString(1)), rs.getString(2)) to rs.getString(3) }.list()
        if (tick !in 0..recorded.size) throw ApiException(ErrorCode.VALIDATION_FAILED, "tick must be between 0 and ${recorded.size}")
        @Suppress("UNCHECKED_CAST")
        val checkpoints = jdbc.sql("SELECT tick, state::text, state_digest FROM ir_checkpoints WHERE session_id = ?").param(sessionId)
            .query { rs, _ -> rs.getInt(1) to (json.readValue(rs.getString(2), Map::class.java) as Map<String, Any?> to rs.getString(3)) }.list().toMap()
        val (state, from) = Checkpoints.seek(DrillSeeds.of(seedBytes, DrillSeeds.INCIDENT), recorded.map { it.first }, tick, checkpoints)
        val view = SystemStateView(state.tick, state.leakedSyntheticRecords, state.availabilityBps / 10_000.0, state.workloadSuccessBps / 10_000.0, state.evidenceCoverageBps / 10_000.0)
        return ReplayStateView(tick, "SIMULATED", engine, view, state.digest(), from, if (tick == 0) null else recorded[tick - 1].second)
    }
}

@RestController
class ReplayController(private val replay: ReplayService) {
    @GetMapping("/v1/sessions/{id}/replay")
    fun manifest(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @RequestParam(required = false) fromSeq: Long?, @RequestParam(required = false) toSeq: Long?) =
        replay.manifest(principal, id, fromSeq, toSeq)

    @GetMapping("/v1/sessions/{id}/replay/chunks/{fromSeq}")
    fun chunk(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @PathVariable fromSeq: Long, @RequestParam(required = false) toSeq: Long?) =
        replay.chunk(principal, id, fromSeq, toSeq)

    @GetMapping("/v1/sessions/{id}/replay/state")
    fun state(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @RequestParam tick: Int) = replay.state(principal, id, tick)
}
