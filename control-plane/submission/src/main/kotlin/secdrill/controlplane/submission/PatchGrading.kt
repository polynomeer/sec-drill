package secdrill.controlplane.submission

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.evidence.ArtifactService
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.identity.WorkloadPrincipal
import secdrill.controlplane.platform.AsyncProperties
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.GateReport
import secdrill.execution.protocol.GradeAssignment
import secdrill.execution.protocol.JobLease
import secdrill.execution.protocol.JobOutcome
import secdrill.execution.protocol.JobResultReport
import secdrill.execution.protocol.ObjectiveObservation
import secdrill.execution.protocol.PatchObservation
import secdrill.execution.protocol.PatchRunOutcome
import secdrill.execution.protocol.PatchTask
import secdrill.kernel.Digests
import secdrill.kernel.EvidenceSource
import secdrill.kernel.GateResult
import secdrill.kernel.SubmissionKind
import secdrill.kernel.TrustLevel
import secdrill.kernel.Verdict
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID

/**
 * Python patch grading (20, T08, ADR 0009). The runner reports what the supervisor observed per hidden test; this
 * service alone maps tests to gates from the grader-only oracle (`expected: deny` is a security test, `allow` a
 * regression test) and decides:
 *
 * | run                          | result                                   |
 * |------------------------------|------------------------------------------|
 * | compile error                | FAIL / NOT_VERIFIED, gate compile FAIL   |
 * | every security and regression test held | PASS / VERIFIED               |
 * | any mandatory test failed    | FAIL / NOT_VERIFIED                      |
 * | platform error, missing results | retried, then SYSTEM_ERROR / INCONCLUSIVE |
 * | grader material missing      | SYSTEM_ERROR (content invalid), no retry |
 *
 * Learners see only the gate categories, never test ids, requests or expectations.
 */
@Service
class PatchGradingService(
    private val jdbc: JdbcClient,
    private val jobs: JobLeaseService,
    private val ctf: CtfGradingService,
    private val artifacts: ArtifactService,
    private val store: ArtifactStore,
    private val ledger: LedgerAppender,
    private val grading: GradingProperties,
    private val async: AsyncProperties,
    private val json: JsonMapper,
) {
    companion object {
        const val POLICY = "python-patch/1"
        const val HIDDEN_TESTS = "private/hidden-tests.json"
    }

    private data class Grader(val image: String, val memoryMiB: Int, val pids: Int, val plan: String, val security: Set<String>, val regression: Set<String>)

    @Transactional
    fun claim(runnerId: String): GradeAssignment? {
        val candidates = jdbc.sql(
            """SELECT j.id FROM jobs j JOIN submissions s ON s.id = j.submission_id
               WHERE j.kind = 'GRADE' AND j.state = 'DISPATCHED' AND s.kind = 'PATCH' AND j.attempt < ? ORDER BY j.dispatched_at, j.id LIMIT 10""",
        ).param(async.maxAttempts).query(UUID::class.java).list().filterNotNull()
        for (candidate in candidates) {
            val lease = jobs.claimJob(candidate, runnerId) ?: continue
            val submission = checkNotNull(lease.submissionId)
            val files = bundleFiles(submission)
            val grader = grader(submission)
            if (files == null || grader == null) {
                // Missing grader material or a damaged bundle: not the learner's fault and not retryable (20).
                jobs.start(lease)
                jobs.complete(lease, report(lease, JobOutcome.CONTENT_INVALID, null, emptyList(), "missing grading material"))
                continue
            }
            return GradeAssignment(lease, null, PatchTask(grader.image, files, grader.plan, grading.timeoutSeconds, grader.memoryMiB, grader.pids))
        }
        return null
    }

    @Transactional
    fun result(runnerId: String, lease: JobLease, observation: PatchObservation): Ack {
        if (!ctf.holds(runnerId, lease)) return ctf.stale(runnerId, lease, "patch-result")
        val submission = checkNotNull(lease.submissionId)
        val grader = grader(submission)
        val report = when {
            grader == null -> report(lease, JobOutcome.CONTENT_INVALID, null, emptyList(), observation)
            observation.outcome == PatchRunOutcome.PLATFORM_ERROR -> report(lease, JobOutcome.PLATFORM_ERROR, null, emptyList(), observation)
            observation.outcome == PatchRunOutcome.COMPILE_FAILED ->
                report(lease, JobOutcome.COMPLETED, Verdict.FAIL, listOf(GateReport("compile", GateResult.FAIL)), observation)
            // Every hidden test must have a result; a partial report means the collector lost data (20).
            !observation.results.keys.containsAll(grader.security + grader.regression) -> report(lease, JobOutcome.PLATFORM_ERROR, null, emptyList(), observation)
            else -> {
                val security = grader.security.all { observation.results.getValue(it) }
                val regression = grader.regression.all { observation.results.getValue(it) }
                val gates = listOf(GateReport("compile", GateResult.PASS), GateReport("security", gate(security)), GateReport("regression", gate(regression)))
                report(lease, JobOutcome.COMPLETED, if (security && regression) Verdict.PASS else Verdict.FAIL, gates, observation)
            }
        }
        val ack = jobs.complete(lease, report)
        if (ack == Ack.ACCEPTED && grader != null && observation.outcome != PatchRunOutcome.PLATFORM_ERROR) {
            val (sessionId, bundleDigest) = jdbc.sql("SELECT session_id, safe_metadata->>'bundleDigest' FROM submissions WHERE id = ?").param(submission)
                .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.single()
            ledger.append(sessionId, "TEST_RESULT", EvidenceSource.SUPERVISOR, TrustLevel.OBSERVED, mapOf(
                "submissionId" to submission, "bundleDigest" to bundleDigest, "outcome" to observation.outcome.name,
                "securityPassed" to grader.security.count { observation.results[it] == true }, "securityTotal" to grader.security.size,
                "regressionPassed" to grader.regression.count { observation.results[it] == true }, "regressionTotal" to grader.regression.size,
                "outputDigest" to observation.outputDigest,
            ))
        }
        return ack
    }

    private fun gate(passed: Boolean) = if (passed) GateResult.PASS else GateResult.FAIL

    private fun report(lease: JobLease, outcome: JobOutcome, verdict: Verdict?, gates: List<GateReport>, observation: Any): JobResultReport {
        val digest = Digests.canonical(mapOf("jobId" to lease.jobId.toString(), "attempt" to lease.attempt, "observation" to observation.toString()))
        return JobResultReport(outcome, verdict, digest, POLICY, fake = false, gates = gates, unverifiedIsolation = !grading.isolationVerified)
    }

    /** The learner's files from the stored canonical bundle, after checking the digest recorded at acceptance. */
    private fun bundleFiles(submission: UUID): Map<String, String>? {
        val (artifactId, recorded) = jdbc.sql("SELECT artifact_id, safe_metadata->>'bundleDigest' FROM submissions WHERE id = ?").param(submission)
            .query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.optional().orElse(null) ?: return null
        val bundle = artifacts.readInternal(artifactId ?: return null)?.let { runCatching { json.readTree(it.bytes) }.getOrNull() } ?: return null
        if (bundle["bundleDigest"]?.asString() != recorded) return null
        return bundle["files"]?.properties()?.associate { it.key to it.value.asString() }
    }

    /** Image, limits, hidden test plan and gate classification of the Session's pinned version (grader-only). */
    private fun grader(submission: UUID): Grader? {
        val (manifestText, oracleKey) = jdbc.sql(
            """SELECT sv.public_manifest::text, sv.oracle_key FROM submissions s JOIN sessions se ON se.id = s.session_id
               JOIN scenario_versions sv ON sv.id = se.scenario_version_id WHERE s.id = ?""",
        ).param(submission).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null) ?: return null
        val manifest = json.readTree(manifestText)
        val private = runCatching { store.get(oracleKey) }.getOrNull()?.let { runCatching { json.readTree(it) }.getOrNull() } ?: return null
        val oracle: JsonNode = private["oracle"] ?: return null
        val plan = private["files"]?.get(HIDDEN_TESTS)?.asString()?.let { String(Base64.getDecoder().decode(it)) } ?: return null
        val planIds = runCatching { json.readTree(plan)["tests"].values().map { it["id"].asString() }.toSet() }.getOrNull() ?: return null
        val tests = oracle["hiddenTests"]?.values()?.map { it["id"].asString() to it["expected"].asString() } ?: return null
        if (tests.isEmpty() || tests.map { it.first }.toSet() != planIds) return null
        val image = manifest["runtime"]?.get("imageDigest")?.asString()?.takeIf { Regex("^sha256:[a-f0-9]{64}$").matches(it) } ?: return null
        fun limit(name: String, max: Int) = (manifest["runtime"]?.get(name)?.asInt() ?: max).coerceIn(1, max)
        return Grader(image, limit("memoryMiB", 2048), limit("pids", 256), plan,
            tests.filter { it.second == "deny" }.map { it.first }.toSet(), tests.filter { it.second == "allow" }.map { it.first }.toSet())
    }
}

data class ClaimBody(val kinds: Set<SubmissionKind> = setOf(SubmissionKind.FLAG))
data class LeaseBody(val lease: JobLease)
data class ObservedBody(val lease: JobLease, val observation: ObjectiveObservation)
data class PatchResultBody(val lease: JobLease, val observation: PatchObservation)

/** Internal grading API for runners (AGENT credential, ADR 0008, ADR 0009). */
@RestController
class GradeJobController(private val ctf: CtfGradingService, private val patches: PatchGradingService) {
    @PostMapping("/internal/v1/grade-jobs/claim")
    fun claim(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody(required = false) body: ClaimBody?): ResponseEntity<GradeAssignment> {
        val kinds = body?.kinds ?: setOf(SubmissionKind.FLAG)
        // ctf.claim serves both FLAG and OBJECTIVE (both observe this runner's Lab, 09, ADR 0014).
        val assignment = (if (SubmissionKind.FLAG in kinds || SubmissionKind.OBJECTIVE in kinds) ctf.claim(runner.runnerId) else null)
            ?: (if (SubmissionKind.PATCH in kinds) patches.claim(runner.runnerId) else null)
        return assignment?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }

    @PostMapping("/internal/v1/grade-jobs/start")
    fun start(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: LeaseBody) = mapOf("ack" to ctf.start(runner.runnerId, body.lease))

    @PostMapping("/internal/v1/grade-jobs/heartbeat")
    fun heartbeat(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: LeaseBody) = mapOf("ack" to ctf.heartbeat(runner.runnerId, body.lease))

    @PostMapping("/internal/v1/grade-jobs/observed")
    fun observed(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: ObservedBody) =
        mapOf("ack" to ctf.observed(runner.runnerId, body.lease, body.observation))

    @PostMapping("/internal/v1/grade-jobs/patch-result")
    fun patchResult(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: PatchResultBody) =
        mapOf("ack" to patches.result(runner.runnerId, body.lease, body.observation))
}
