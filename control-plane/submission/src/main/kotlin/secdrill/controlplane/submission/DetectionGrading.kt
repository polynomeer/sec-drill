package secdrill.controlplane.submission

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.controlplane.evidence.ArtifactService
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.platform.AsyncProperties
import secdrill.execution.protocol.DimensionReport
import secdrill.execution.protocol.GateReport
import secdrill.execution.protocol.JobLease
import secdrill.execution.protocol.JobOutcome
import secdrill.execution.protocol.JobResultReport
import secdrill.kernel.Digests
import secdrill.kernel.EvidenceSource
import secdrill.kernel.GateResult
import secdrill.kernel.TrustLevel
import secdrill.kernel.Verdict
import secdrill.simulation.DetectionGate
import secdrill.simulation.DetectionMetrics
import secdrill.simulation.DetectionScorer
import secdrill.simulation.DetectionThresholds
import secdrill.simulation.DrillSeeds
import secdrill.simulation.Gate
import secdrill.simulation.RuleBudgetExceeded
import secdrill.simulation.RuleEvaluator
import secdrill.simulation.RuleParser
import secdrill.simulation.SyntheticLogs
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Detection grading (21, T10, ADR 0010). Runs in the Control Plane: the rule is a bounded AST evaluated over
 * synthetic logs, not learner code, so no isolation runtime is involved. The verdict uses the hidden holdout only
 * (renamed actors and routes from a seed the learner cannot derive); the training metrics are shown as dimensions.
 * Evidence is SIMULATED: the logs are synthetic, not observed from a Lab.
 *
 * | holdout                          | result                                  |
 * |----------------------------------|-----------------------------------------|
 * | recall, precision, p95 latency met | PASS, gate detection PASS             |
 * | any threshold missed, no alerts  | FAIL, gate detection FAIL               |
 * | evaluation budget exceeded       | FAIL, gate resource_limit FAIL (20)     |
 * | dataset without episodes         | SYSTEM_ERROR, gate detection INCONCLUSIVE |
 * | thresholds or rule missing       | content invalid SYSTEM_ERROR            |
 */
@Service
class DetectionGradingService(
    private val jdbc: JdbcClient,
    private val jobs: JobLeaseService,
    private val artifacts: ArtifactService,
    private val store: ArtifactStore,
    private val ledger: LedgerAppender,
    private val async: AsyncProperties,
    private val json: JsonMapper,
) {
    companion object {
        const val POLICY = "detection/1"
        const val WORKER = "control-detection"
    }

    @Scheduled(fixedDelayString = "\${secdrill.async.sweep-interval:1s}")
    fun scheduled() {
        if (async.schedulingEnabled) while (runOnce() != null) Unit
    }

    /** Grades at most one DETECTION job; returns its lease or null when none was claimable. */
    @Transactional
    fun runOnce(): JobLease? {
        val candidate = jdbc.sql(
            """SELECT j.id FROM jobs j JOIN submissions s ON s.id = j.submission_id
               WHERE j.kind = 'GRADE' AND j.state = 'DISPATCHED' AND s.kind = 'DETECTION' AND j.attempt < ? ORDER BY j.dispatched_at, j.id LIMIT 1""",
        ).param(async.maxAttempts).query(UUID::class.java).optional().orElse(null) ?: return null
        val lease = jobs.claimJob(candidate, WORKER) ?: return null
        jobs.start(lease)
        jobs.complete(lease, grade(lease))
        return lease
    }

    private fun grade(lease: JobLease): JobResultReport {
        val submission = checkNotNull(lease.submissionId)
        val (sessionId, seed, artifactId, oracleKey) = jdbc.sql(
            """SELECT s.session_id, se.seed, s.artifact_id, sv.oracle_key FROM submissions s JOIN sessions se ON se.id = s.session_id
               JOIN scenario_versions sv ON sv.id = se.scenario_version_id WHERE s.id = ?""",
        ).param(submission).query { rs, _ -> Quad(rs.getObject(1, UUID::class.java), rs.getBytes(2), rs.getObject(3, UUID::class.java), rs.getString(4)) }.single()
        fun report(outcome: JobOutcome, verdict: Verdict?, gates: List<GateReport>, dimensions: List<DimensionReport> = emptyList()) = JobResultReport(
            outcome, verdict, Digests.canonical(mapOf("jobId" to lease.jobId.toString(), "attempt" to lease.attempt, "verdict" to verdict?.name, "gates" to gates.map { it.toString() })),
            POLICY, fake = false, gates = gates, dimensions = dimensions,
        )
        val thresholds = runCatching { store.get(oracleKey) }.getOrNull()?.let { runCatching { json.readTree(it)["oracle"]["detection"] }.getOrNull() }?.let {
            DetectionThresholds(it["minRecallBps"].asInt(), it["minPrecisionBps"].asInt(), it["maxP95LatencySeconds"].asLong())
        }
        val rule = artifactId?.let { artifacts.readInternal(it) }?.let { runCatching { RuleParser.parse(json.readTree(it.bytes)["rule"]) }.getOrNull() }
        if (thresholds == null || rule == null) return report(JobOutcome.CONTENT_INVALID, null, emptyList())

        val evaluator = RuleEvaluator()
        val training = SyntheticLogs.generate(DrillSeeds.of(seed, DrillSeeds.TRAINING), SyntheticLogs.Variant.TRAINING)
        val holdout = SyntheticLogs.generate(DrillSeeds.of(seed, DrillSeeds.HOLDOUT), SyntheticLogs.Variant.HOLDOUT)
        val (trainingMetrics, holdoutMetrics) = try {
            DetectionScorer.score(evaluator.alerts(rule, training.events), training.truth) to DetectionScorer.score(evaluator.alerts(rule, holdout.events), holdout.truth)
        } catch (exceeded: RuleBudgetExceeded) {
            return report(JobOutcome.COMPLETED, Verdict.FAIL, listOf(GateReport("resource_limit", GateResult.FAIL)))
        }
        val dimensions = dimensions("training", trainingMetrics) + dimensions("holdout", holdoutMetrics)
        ledger.append(sessionId, "TEST_RESULT", EvidenceSource.SIMULATOR, TrustLevel.SIMULATED, mapOf(
            "submissionId" to submission, "dataset" to "holdout", "generator" to SyntheticLogs.VERSION,
            "truePositives" to holdoutMetrics.truePositives, "falsePositives" to holdoutMetrics.falsePositives,
            "falseNegatives" to holdoutMetrics.falseNegatives, "trueNegatives" to holdoutMetrics.trueNegatives,
            "p95LatencySeconds" to holdoutMetrics.p95LatencySeconds,
        ))
        return when (DetectionGate.of(holdoutMetrics, thresholds)) {
            Gate.PASS -> report(JobOutcome.COMPLETED, Verdict.PASS, listOf(GateReport("detection", GateResult.PASS)), dimensions)
            Gate.FAIL -> report(JobOutcome.COMPLETED, Verdict.FAIL, listOf(GateReport("detection", GateResult.FAIL)), dimensions)
            Gate.INCONCLUSIVE -> report(JobOutcome.INCONCLUSIVE, null, listOf(GateReport("detection", GateResult.INCONCLUSIVE)), dimensions)
        }
    }

    /** Ratios as basis points; an N/A ratio is left out rather than reported as 0 (21). */
    private fun dimensions(prefix: String, metrics: DetectionMetrics) = listOfNotNull(
        metrics.recall.bps?.let { DimensionReport("$prefix.recall", it) },
        metrics.precision.bps?.let { DimensionReport("$prefix.precision", it) },
        metrics.f1.bps?.let { DimensionReport("$prefix.f1", it) },
        metrics.falsePositiveRate.bps?.let { DimensionReport("$prefix.falsePositiveRate", it) },
    )

    private data class Quad(val sessionId: UUID, val seed: ByteArray, val artifactId: UUID?, val oracleKey: String)
}
