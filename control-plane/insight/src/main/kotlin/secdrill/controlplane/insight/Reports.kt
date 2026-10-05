package secdrill.controlplane.insight

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.platform.AsyncProperties
import secdrill.controlplane.submission.JobLeaseService
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import secdrill.kernel.IrActionType
import secdrill.kernel.Rfc3339
import secdrill.learning.Candidate
import secdrill.learning.Exposure
import secdrill.learning.RecommendationPolicy
import secdrill.simulation.DrillSeeds
import secdrill.simulation.IncidentModel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID

data class DimensionView(
    val key: String, val status: String, val score: Double?, val representation: String, val evaluationId: UUID?, val evidenceIds: List<UUID>,
)
data class RecommendationView(val scenarioId: UUID, val scenarioVersionId: UUID, val title: String, val scoreBps: Int, val reasons: List<String>, val terms: Map<String, Int>)
data class ReportView(
    val sessionId: UUID, val revision: Int, val policyVersion: String, val createdAt: String, val evaluationRefs: List<UUID>, val summary: String,
    val exposure: String, val dimensions: List<DimensionView>, val evidenceIds: List<UUID>, val recommendedScenarioVersionIds: List<UUID>,
    val recommendations: List<RecommendationView>, val scopeLimitations: List<String>, val changeReason: String?,
)

/**
 * Session reports (10, ADR 0012). Built from the active evaluations and the ledger, never from UI state. Every
 * dimension says how its result was produced (observed by a supervisor or collector, server-verified, or a model
 * recomputation) and links the evidence it rests on. A revision is fixed once written: a re-grade adds a new one.
 */
@Service
class ReportService(
    private val jdbc: JdbcClient,
    private val facts: LearningFacts,
    private val skills: SkillService,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    companion object {
        const val POLICY = "report-v1"
        private val PURPLE = listOf("attack", "observation", "detection", "response", "patch", "regression", "postmortem")
    }

    private data class Eval(val id: UUID, val submissionId: UUID, val kind: String, val verdict: String, val demo: Boolean, val gates: JsonNode, val dimensions: JsonNode)

    /** Writes a new revision if the evaluations changed since the last one; returns the current revision. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NESTED)
    fun generate(sessionId: UUID): Int {
        val (owner, mode, manifestText) = jdbc.sql(
            "SELECT s.owner_id, s.mode, sv.public_manifest::text FROM sessions s JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.id = ?",
        ).param(sessionId).query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3)) }.single()
        val manifest = json.readTree(manifestText)
        val evals = jdbc.sql(
            """SELECT e.id, s.id, s.kind, e.verdict, e.demo, e.gates::text, e.dimensions::text FROM evaluations e JOIN submissions s ON s.id = e.submission_id
               WHERE s.session_id = ? AND e.is_active ORDER BY e.created_at, e.id""",
        ).param(sessionId).query { rs, _ ->
            Eval(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                json.readTree(rs.getString(6)), json.readTree(rs.getString(7)))
        }.list()
        val refs = evals.map { it.id }
        val latest = jdbc.sql("SELECT revision, evaluation_refs::text FROM reports WHERE session_id = ? ORDER BY revision DESC LIMIT 1").param(sessionId)
            .query { rs, _ -> rs.getInt(1) to json.readTree(rs.getString(2)).values().map { UUID.fromString(it.asString()) } }.optional().orElse(null)
        if (latest != null && latest.second == refs) return latest.first

        val sessions = facts.sessions(owner)
        val exposure = facts.exposure(sessionId, sessions)
        val deduction = jdbc.sql("SELECT coalesce(max(level), 0) FROM hint_grants WHERE session_id = ?").param(sessionId).query(Int::class.java).single()
            .let { (1..it).sumOf { level -> mapOf(1 to 5, 2 to 10, 3 to 20, 4 to 40).getValue(level) } }
        val limitations = mutableListOf<String>()
        val dimensions = mutableListOf<DimensionView>()
        fun anchors(eval: Eval, vararg types: String): List<UUID> = jdbc.sql(
            """SELECT id FROM evidence WHERE session_id = :session AND ((event_type = 'EvaluationCommitted' AND safe_payload->>'evaluationId' = :evaluation)
               OR (event_type IN (:types) AND safe_payload->>'submissionId' = :submission)) ORDER BY seq""",
        ).param("session", sessionId).param("evaluation", eval.id.toString()).param("types", types.toList().ifEmpty { listOf("-") })
            .param("submission", eval.submissionId.toString()).query(UUID::class.java).list().filterNotNull()
        fun gate(eval: Eval, key: String) = eval.gates.values().firstOrNull { it["key"].asString() == key }?.get("result")?.asString()
        fun status(verdict: String) = when (verdict) { "PASS" -> "PASS"; "FAIL" -> "FAIL"; else -> "INCONCLUSIVE" }
        fun best(kinds: Set<String>) = evals.filter { it.kind in kinds }.let { list -> list.firstOrNull { it.verdict == "PASS" } ?: list.firstOrNull { it.verdict == "FAIL" } ?: list.firstOrNull() }

        val attack = best(setOf("FLAG", "OBJECTIVE"))
        dimensions += if (attack == null) DimensionView("attack", "NOT_ATTEMPTED", null, "SERVER_VERIFIED", null, emptyList()) else {
            val evidence = anchors(attack, "OBJECTIVE_CONFIRMED")
            val observed = jdbc.sql("SELECT count(*) FROM evidence WHERE session_id = ? AND event_type = 'OBJECTIVE_CONFIRMED' AND safe_payload->>'submissionId' = ?")
                .params(sessionId, attack.submissionId.toString()).query(Int::class.java).single() > 0
            DimensionView("attack", status(attack.verdict), if (attack.verdict == "PASS") (100 - deduction).coerceAtLeast(0).toDouble() else if (attack.verdict == "FAIL") 0.0 else null,
                if (observed) "OBSERVED" else "SERVER_VERIFIED", attack.id, evidence)
        }
        if (mode == "PURPLE") {
            dimensions += DimensionView("observation", "NOT_EVALUATED", null, "USER_REPORTED", null, emptyList())
            limitations += "관측·조사 차원은 timeline 답안 채점이 없어 평가하지 않았습니다."
            val detection = best(setOf("DETECTION"))
            dimensions += if (detection == null) DimensionView("detection", "NOT_ATTEMPTED", null, "SIMULATED", null, emptyList()) else
                DimensionView("detection", status(detection.verdict),
                    detection.dimensions.values().firstOrNull { it["key"].asString() == "holdout.f1" }?.get("scoreBps")?.asInt()?.div(100.0) ?: if (detection.verdict == "FAIL") 0.0 else null,
                    "SIMULATED", detection.id, anchors(detection, "TEST_RESULT"))
            dimensions += response(sessionId)
            val patch = best(setOf("PATCH"))
            if (patch == null) {
                dimensions += DimensionView("patch", "NOT_ATTEMPTED", null, "OBSERVED", null, emptyList())
                dimensions += DimensionView("regression", "NOT_ATTEMPTED", null, "OBSERVED", null, emptyList())
            } else {
                val evidence = anchors(patch, "TEST_RESULT")
                dimensions += DimensionView("patch", status(patch.verdict), when (patch.verdict) { "PASS" -> 100.0; "FAIL" -> 0.0; else -> null }, "OBSERVED", patch.id, evidence)
                val regression = gate(patch, "regression")
                dimensions += DimensionView("regression", regression?.let { if (it == "PASS") "PASS" else if (it == "FAIL") "FAIL" else "INCONCLUSIVE" } ?: "NOT_EVALUATED",
                    regression?.let { if (it == "PASS") 100.0 else if (it == "FAIL") 0.0 else null }, "OBSERVED", patch.id, evidence)
            }
            val postmortems = jdbc.sql("SELECT count(*) FROM submissions WHERE session_id = ? AND kind = 'POSTMORTEM'").param(sessionId).query(Int::class.java).single()
            dimensions += DimensionView("postmortem", if (postmortems > 0) "NOT_EVALUATED" else "NOT_ATTEMPTED", null, "USER_REPORTED", null, emptyList())
            if (postmortems > 0) limitations += "회고는 의미 품질 자동 평가가 없어 제출 여부만 확인했습니다(experimental)."
            limitations += "대응 차원은 모델 재계산(SIMULATED)이며 실제 Lab을 조치한 결과가 아닙니다."
            dimensions.sortBy { PURPLE.indexOf(it.key) }
        }
        if (evals.any { it.demo } || jdbc.sql("SELECT count(*) FROM labs WHERE session_id = ? AND NOT isolation_verified").param(sessionId).query(Int::class.java).single() > 0) {
            limitations += "격리가 검증되지 않은 환경의 결과가 있어 데모 결과로만 보아야 합니다."
        }
        if (evals.any { it.verdict == "SYSTEM_ERROR" }) limitations += "플랫폼이 판정을 확정하지 못한 제출이 있습니다(학습자 실패가 아님)."
        if (exposure != Exposure.INDEPENDENT) limitations += "힌트나 이전 Session의 도움을 받은 결과입니다(누적 감점 ${deduction}점)."

        val recommendations = recommend(owner, sessionId, manifest)
        val revision = (latest?.first ?: 0) + 1
        val reportExposure = when (exposure) { Exposure.INDEPENDENT -> "INDEPENDENT"; Exposure.SOLUTION_EXPOSED -> "SOLUTION_EXPOSED"; else -> "GUIDED" }
        val passed = dimensions.count { it.status == "PASS" }
        val view = ReportView(
            sessionId, revision, POLICY, Rfc3339.format(clock.instant()), refs,
            "${manifest["title"]?.asString() ?: "사건"} · $mode: 평가된 차원 ${dimensions.count { it.status in setOf("PASS", "FAIL") }}개 중 ${passed}개 통과",
            reportExposure, dimensions, dimensions.flatMap { it.evidenceIds }.distinct(), recommendations.map { it.scenarioVersionId }, recommendations,
            limitations, if (latest != null) "평가가 바뀌었습니다(재채점 또는 새 결과). 이전 revision ${latest.first}은 그대로 남아 있습니다." else null,
        )
        jdbc.sql("INSERT INTO reports(id, session_id, revision, policy_version, evaluation_refs, payload) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)")
            .params(UUID.randomUUID(), sessionId, revision, POLICY, json.writeValueAsString(refs.map(UUID::toString)), json.writeValueAsString(view)).update()
        return revision
    }

    /** The response dimension comes from the IR model (SIMULATED), recomputed from the recorded actions. */
    private fun response(sessionId: UUID): DimensionView {
        val (seed, engine) = jdbc.sql("SELECT seed, engine_version FROM sessions WHERE id = ?").param(sessionId).query { rs, _ -> rs.getBytes(1) to rs.getString(2) }.single()
        val actions = jdbc.sql("SELECT action_type, target FROM applied_actions WHERE session_id = ? ORDER BY seq").param(sessionId)
            .query { rs, _ -> IncidentModel.Action(IrActionType.valueOf(rs.getString(1)), rs.getString(2)) }.list()
        val anchors = jdbc.sql("SELECT id FROM evidence WHERE session_id = ? AND event_type = 'ActionApplied' ORDER BY seq").param(sessionId).query(UUID::class.java).list().filterNotNull()
        if (actions.isEmpty() || engine != IncidentModel.ENGINE) return DimensionView("response", if (actions.isEmpty()) "NOT_ATTEMPTED" else "INCONCLUSIVE", null, "SIMULATED", null, anchors)
        val state = IncidentModel.replay(DrillSeeds.of(seed, DrillSeeds.INCIDENT), actions)
        val contained = state.containedAtTick != null
        // Containment earns credit only to the extent normal work still succeeds (21: blocking everything shows its cost).
        return DimensionView("response", if (contained) "PASS" else "FAIL", if (contained) state.workloadSuccessBps / 100.0 else 0.0, "SIMULATED", null, anchors)
    }

    private fun recommend(owner: UUID, sessionId: UUID, manifest: JsonNode): List<RecommendationView> {
        val (skillViews, watermark) = skills.project(owner)
        val sessions = facts.sessions(owner)
        val exposureByFamily = sessions.values.groupBy { it.family }.mapValues { (_, list) -> list.map { facts.exposure(it.id, sessions) } }
        val candidates = jdbc.sql(
            """SELECT DISTINCT ON (scenario_id) scenario_id, id, public_manifest::text FROM scenario_versions WHERE status = 'PUBLISHED' AND scenario_id <> ?
               ORDER BY scenario_id, version_no DESC""",
        ).param(UUID.fromString(manifest["scenarioId"].asString())).query { rs, _ ->
            val m = json.readTree(rs.getString(3))
            Candidate(rs.getObject(1, UUID::class.java).toString(), rs.getObject(2, UUID::class.java).toString(), m["title"]?.asString() ?: "", m["family"]?.asString() ?: "",
                m["modes"]?.values()?.map { it.asString() } ?: emptyList(), m["competencyTags"]?.values()?.map { it.asString() } ?: emptyList(), m["estimatedMinutes"]?.asInt() ?: 0)
        }.list()
        val preferred = sessions.values.groupingBy { it.mode }.eachCount().maxByOrNull { it.value }?.key
        val ranked = RecommendationPolicy.rank(
            candidates, skillViews, sessions.values.map { it.family }.toSet(),
            exposureByFamily.filterValues { Exposure.SOLUTION_EXPOSED in it }.keys, exposureByFamily.filterValues { it.any { e -> e == Exposure.GUIDED } }.keys, preferred,
        )
        val payload = mapOf(
            "policyVersion" to RecommendationPolicy.VERSION, "sourceWatermark" to watermark, "sessionId" to sessionId.toString(),
            "candidates" to candidates.size, "chosen" to ranked.map { mapOf("scenarioVersionId" to it.candidate.scenarioVersionId, "scoreBps" to it.scoreBps, "terms" to it.terms, "reasons" to it.reasons.map { r -> r.name }) },
            "exposureHistory" to exposureByFamily.mapValues { (_, list) -> list.map { it.name } },
        )
        jdbc.sql("INSERT INTO recommendations(id, user_id, session_id, policy_version, source_watermark, payload) VALUES (?, ?, ?, ?, ?, ?::jsonb)")
            .params(UUID.randomUUID(), owner, sessionId, RecommendationPolicy.VERSION, watermark, json.writeValueAsString(payload)).update()
        return ranked.map {
            RecommendationView(UUID.fromString(it.candidate.scenarioId), UUID.fromString(it.candidate.scenarioVersionId), it.candidate.title, it.scoreBps, it.reasons.map { r -> r.name }, it.terms)
        }
    }

    fun read(principal: LearnerPrincipal, guard: OwnershipGuard, sessionId: UUID, revision: Int?): JsonNode {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val sql = "SELECT payload::text FROM reports WHERE session_id = ?" + (if (revision != null) " AND revision = ?" else "") + " ORDER BY revision DESC LIMIT 1"
        val args = listOfNotNull<Any>(sessionId, revision)
        return jdbc.sql(sql).params(args).query(String::class.java).optional().map { json.readTree(it) }.orElseThrow {
            if (revision != null) ApiException(ErrorCode.NOT_FOUND, "Resource not found") else ApiException(ErrorCode.NOT_READY, "The report is not ready yet")
        }
    }
}

/**
 * REPORT jobs (13: SUBMITTED → EVALUATING → COMPLETED). Created by finish and by re-grades of finished Sessions.
 * Runs in the Control Plane; a failure retries, and after the last attempt the Session is EVALUATION_FAILED.
 */
@Service
class ReportWorker(
    private val jdbc: JdbcClient,
    private val jobs: JobLeaseService,
    private val reports: ReportService,
    private val async: AsyncProperties,
) {
    @Scheduled(fixedDelayString = "\${secdrill.async.sweep-interval:1s}")
    fun scheduled() {
        if (async.schedulingEnabled) while (runOnce() != null) Unit
    }

    @Transactional
    fun runOnce(): UUID? {
        val (jobId, sessionId) = jdbc.sql("SELECT id, session_id FROM jobs WHERE kind = 'REPORT' AND state = 'DISPATCHED' AND attempt < ? ORDER BY dispatched_at, id LIMIT 1")
            .param(async.maxAttempts).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }.optional().orElse(null) ?: return null
        val lease = jobs.claimJob(jobId, "control-report") ?: return null
        jdbc.sql("UPDATE sessions SET status = 'EVALUATING' WHERE id = ? AND status = 'SUBMITTED'").param(sessionId).update()
        try {
            reports.generate(sessionId)
            jdbc.sql("UPDATE jobs SET state = 'SUCCEEDED', worker_id = NULL, lease_until = NULL, version = version + 1 WHERE id = ? AND fencing_token = ?")
                .params(jobId, lease.fencingToken).update()
            jdbc.sql("UPDATE sessions SET status = 'COMPLETED', version = version + 1 WHERE id = ? AND status IN ('EVALUATING', 'EVALUATION_FAILED')").param(sessionId).update()
        } catch (error: RuntimeException) {
            val final = lease.attempt >= async.maxAttempts
            jdbc.sql("UPDATE jobs SET state = ?, last_error = 'PLATFORM_ERROR', worker_id = NULL, lease_until = NULL, version = version + 1 WHERE id = ?")
                .params(if (final) "FAILED" else "DISPATCHED", jobId).update()
            if (final) jdbc.sql("UPDATE sessions SET status = 'EVALUATION_FAILED', version = version + 1 WHERE id = ? AND status = 'EVALUATING'").param(sessionId).update()
        }
        return jobId
    }
}

@RestController
class ReportController(private val reports: ReportService, private val guard: OwnershipGuard) {
    @GetMapping("/v1/sessions/{id}/report")
    fun report(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @RequestParam(required = false) revision: Int?) =
        reports.read(principal, guard, id, revision)
}
