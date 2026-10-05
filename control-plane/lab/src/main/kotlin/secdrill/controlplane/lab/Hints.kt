package secdrill.controlplane.lab

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.EvidenceSource
import secdrill.kernel.FieldError
import secdrill.kernel.SessionStatus
import secdrill.kernel.TrustLevel
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** OpenAPI `Hint`. */
data class HintView(val challengeId: UUID, val level: Int, val text: String, val totalPointDeduction: Int)

/**
 * Hints (05, 06, 09, ADR 0011): the next level of a challenge on request, from the grader-only oracle. Levels open in
 * order; asking for an earlier level again returns it with no further deduction. H1 5, H2 10, H3 20, H4 40 points,
 * cumulative, at most 75. The ledger keeps the level and deduction (HINT_GRANTED), never the text.
 */
@Service
class HintService(
    private val jdbc: JdbcClient,
    private val guard: OwnershipGuard,
    private val store: ArtifactStore,
    private val ledger: LedgerAppender,
    private val json: JsonMapper,
) {
    companion object {
        private val deduction = mapOf(1 to 5, 2 to 10, 3 to 20, 4 to 40)
        fun totalDeduction(levels: Int) = (1..levels).sumOf { deduction.getValue(it) }
    }

    @Transactional
    fun request(principal: LearnerPrincipal, sessionId: UUID, challengeId: UUID, level: Int): HintView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val (status, manifest, oracleKey) = jdbc.sql(
            """SELECT s.status, sv.public_manifest::text, sv.oracle_key FROM sessions s JOIN scenario_versions sv ON sv.id = s.scenario_version_id
               WHERE s.id = ? FOR UPDATE OF s""",
        ).param(sessionId).query { rs, _ -> Triple(SessionStatus.valueOf(rs.getString(1)), json.readTree(rs.getString(2)), rs.getString(3)) }.single()
        if (manifest["challenges"]?.values()?.none { it["id"]?.asString() == challengeId.toString() } != false) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Hint request is invalid", ErrorDetails(fieldErrors = listOf(FieldError("challengeId", "is not a challenge of this scenario"))))
        }
        val granted = jdbc.sql("SELECT coalesce(max(level), 0) FROM hint_grants WHERE session_id = ? AND challenge_id = ?")
            .params(sessionId, challengeId).query(Int::class.java).single()
        if (level > granted + 1) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Hint request is invalid", ErrorDetails(fieldErrors = listOf(FieldError("level", "hints open in order; next is ${granted + 1}"))))
        }
        val text = hintText(oracleKey, challengeId, level)
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "Hint request is invalid", ErrorDetails(fieldErrors = listOf(FieldError("level", "no hint at this level"))))
        if (level <= granted) return HintView(challengeId, level, text, totalDeduction(granted))
        if (status.terminal) throw ApiException(ErrorCode.INVALID_STATE, "Session already ended")
        jdbc.sql("INSERT INTO hint_grants(session_id, challenge_id, level) VALUES (?, ?, ?)").params(sessionId, challengeId, level).update()
        jdbc.sql("UPDATE sessions SET version = version + 1 WHERE id = ?").param(sessionId).update()
        val total = totalDeduction(level)
        ledger.append(sessionId, "HINT_GRANTED", EvidenceSource.CONTROL, TrustLevel.SERVER_VERIFIED,
            mapOf("challengeId" to challengeId, "level" to level, "totalPointDeduction" to total, "guided" to (level >= 3)))
        return HintView(challengeId, level, text, total)
    }

    private fun hintText(oracleKey: String, challengeId: UUID, level: Int): String? {
        val oracle: JsonNode = runCatching { store.get(oracleKey) }.getOrNull()?.let { runCatching { json.readTree(it)["oracle"] }.getOrNull() } ?: return null
        return oracle["hints"]?.values()?.firstOrNull { it["challengeId"]?.asString() == challengeId.toString() && it["level"]?.asInt() == level }?.get("text")?.asString()
    }
}

@RestController
class HintController(private val hints: HintService) {
    @PostMapping("/v1/sessions/{id}/hints")
    fun request(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @RequestBody body: JsonNode): HintView {
        val challenge = body["challengeId"]?.takeIf { it.isString }?.asString()?.let { runCatching { Uuids.parse(it) }.getOrNull() }
        val level = body["level"]?.takeIf { it.isIntegralNumber }?.asInt()
        val unknown = body.propertyNames().toSet() - setOf("challengeId", "level")
        if (challenge == null || level == null || level !in 1..4 || unknown.isNotEmpty()) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Hint request is invalid", ErrorDetails(fieldErrors = listOf(FieldError("$", "challengeId (UUID) and level 1-4 only"))))
        }
        return hints.request(principal, id, challenge, level)
    }
}
