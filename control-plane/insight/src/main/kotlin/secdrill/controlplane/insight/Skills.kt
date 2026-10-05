package secdrill.controlplane.insight

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.kernel.ApiException
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.learning.Exposure
import secdrill.learning.GradedOutcome
import secdrill.learning.SkillPolicy
import secdrill.learning.SkillView
import secdrill.learning.Taxonomy
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class SkillItemView(val key: String, val level: String, val confidence: String, val sampleCount: Int, val familyCount: Int, val successBps: Int?, val evidenceIds: List<String>)
data class SkillPageView(val policyVersion: String, val taxonomyVersion: String, val watermark: String, val skills: List<SkillItemView>)

/** A learner's Sessions as the projection and the report need them: family, mode, help received and lineage. */
data class SessionFacts(val id: UUID, val parent: UUID?, val mode: String, val family: String, val maxHintLevel: Int)

/**
 * Reads what the policies in `:shared:learning` need. Help (hints) propagates down the parent chain: a follow-up
 * Session is never more independent than the Session it continues (05, prompt 12).
 */
@Service
class LearningFacts(private val jdbc: JdbcClient, private val json: JsonMapper) {
    fun sessions(owner: UUID): Map<UUID, SessionFacts> = jdbc.sql(
        """SELECT s.id, s.parent_session_id, s.mode, sv.public_manifest::text,
                  (SELECT coalesce(max(h.level), 0) FROM hint_grants h WHERE h.session_id = s.id)
           FROM sessions s JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.owner_id = ?""",
    ).param(owner).query { rs, _ ->
        SessionFacts(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3),
            json.readTree(rs.getString(4))["family"]?.asString() ?: "unknown", rs.getInt(5))
    }.list().associateBy { it.id }

    /** Highest hint level along the chain; H4 is the full explanation (05). */
    fun exposure(id: UUID, sessions: Map<UUID, SessionFacts>): Exposure {
        var level = 0
        var current = sessions[id]
        val seen = mutableSetOf<UUID>()
        while (current != null && seen.add(current.id)) {
            level = maxOf(level, current.maxHintLevel)
            current = current.parent?.let(sessions::get)
        }
        return when (level) {
            0 -> Exposure.INDEPENDENT
            1, 2 -> Exposure.LIGHT_HINTS
            3 -> Exposure.GUIDED
            else -> Exposure.SOLUTION_EXPOSED
        }
    }

    fun root(id: UUID, sessions: Map<UUID, SessionFacts>): UUID {
        var current = sessions.getValue(id)
        val seen = mutableSetOf(current.id)
        while (current.parent != null && seen.add(current.parent!!)) current = sessions[current.parent] ?: break
        return current.id
    }

    /** Active evaluations only: a re-grade supersedes, it never adds a second sample (10). */
    fun outcomes(owner: UUID): Pair<List<GradedOutcome>, String> {
        val sessions = sessions(owner)
        val rows = jdbc.sql(
            """SELECT e.id, e.verdict, e.demo, e.created_at, s.kind, s.session_id FROM evaluations e
               JOIN submissions s ON s.id = e.submission_id JOIN sessions se ON se.id = s.session_id
               WHERE se.owner_id = ? AND e.is_active ORDER BY e.id""",
        ).param(owner).query { rs, _ ->
            Row(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getBoolean(3), rs.getObject(4, OffsetDateTime::class.java), rs.getString(5), rs.getObject(6, UUID::class.java))
        }.list()
        val outcomes = rows.map { row ->
            val session = sessions.getValue(row.sessionId)
            val parentFamily = session.parent?.let(sessions::get)?.family
            GradedOutcome(
                row.evaluationId.toString(), evaluationEvidence(row.evaluationId), row.kind, session.mode, row.verdict, row.demo, session.family,
                row.createdAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(), exposure(session.id, sessions),
                transfer = parentFamily != null && parentFamily != session.family, correlationRoot = root(session.id, sessions).toString(),
            )
        }
        // The projection is a function of the active evaluations: their ids are its watermark.
        val watermark = "evaluations:" + Digests.canonical(rows.map { it.evaluationId.toString() }).take(16)
        return outcomes to watermark
    }

    fun evaluationEvidence(evaluationId: UUID): List<String> =
        jdbc.sql("SELECT id FROM evidence WHERE event_type = 'EvaluationCommitted' AND safe_payload->>'evaluationId' = ?").param(evaluationId.toString())
            .query(UUID::class.java).list().filterNotNull().map(UUID::toString)

    private data class Row(val evaluationId: UUID, val verdict: String, val demo: Boolean, val createdAt: OffsetDateTime, val kind: String, val sessionId: UUID)
}

@Service
class SkillService(private val facts: LearningFacts) {
    fun project(owner: UUID): Pair<List<SkillView>, String> {
        val (outcomes, watermark) = facts.outcomes(owner)
        return SkillPolicy.project(outcomes) to watermark
    }
}

/** `GET /v1/skills/me` (15). UNKNOWN (not enough evidence) and confidence are separate fields (10). */
@RestController
class SkillController(private val skills: SkillService) {
    @GetMapping("/v1/skills/me")
    fun me(@AuthenticationPrincipal principal: LearnerPrincipal, @RequestParam(required = false) policyVersion: String?): SkillPageView {
        if (policyVersion != null && policyVersion != SkillPolicy.VERSION) throw ApiException(ErrorCode.VALIDATION_FAILED, "Only ${SkillPolicy.VERSION} is available")
        val (views, watermark) = skills.project(principal.userId.value)
        return SkillPageView(SkillPolicy.VERSION, Taxonomy.VERSION, watermark,
            views.map { SkillItemView(it.key, it.level.name, it.confidence.name, it.sampleCount, it.familyCount, it.successBps, it.evidenceIds) })
    }
}
