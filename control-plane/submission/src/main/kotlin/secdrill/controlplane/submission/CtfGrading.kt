package secdrill.controlplane.submission

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.controlplane.ctf.FlagService
import secdrill.controlplane.evidence.ArtifactService
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.platform.AsyncProperties
import secdrill.controlplane.platform.SystemAudit
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.GateReport
import secdrill.execution.protocol.GradeAssignment
import secdrill.execution.protocol.JobLease
import secdrill.execution.protocol.JobOutcome
import secdrill.execution.protocol.JobResultReport
import secdrill.execution.protocol.ObjectiveObservation
import secdrill.execution.protocol.ObjectiveTask
import secdrill.kernel.CanonicalJson
import secdrill.kernel.Digests
import secdrill.kernel.EventType
import secdrill.kernel.EvidenceSource
import secdrill.kernel.GateResult
import secdrill.kernel.TrustLevel
import secdrill.kernel.Verdict
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * CTF flag grading (09, 20, ADR 0008). The verdict is decided here, from two independent facts:
 * - the signed flag receipt written at acceptance (did the flag match this Session, challenge and a live Lab?)
 * - the runner's observation of the target's server-side access records (was the objective actually reached?)
 *
 * | flag | observation          | result                                            |
 * |------|----------------------|---------------------------------------------------|
 * | no   | —                    | FAIL (a wrong flag is a completed FAIL, not an error) |
 * | yes  | qualifying access    | PASS                                              |
 * | yes  | none                 | SYSTEM_ERROR, objective INCONCLUSIVE (never FAIL) |
 * | yes  | not collectable      | platform error, retried, then SYSTEM_ERROR        |
 * | receipt invalid or missing | | platform error, retried, then SYSTEM_ERROR        |
 *
 * Only the runner that hosts the Lab gets the job, and it is never told whether the flag matched.
 */
@Service
class CtfGradingService(
    private val jdbc: JdbcClient,
    private val jobs: JobLeaseService,
    private val flags: FlagService,
    private val artifacts: ArtifactService,
    private val store: ArtifactStore,
    private val ledger: LedgerAppender,
    private val audit: SystemAudit,
    private val async: AsyncProperties,
    private val json: JsonMapper,
) {
    companion object {
        const val POLICY = "ctf-objective/1"
    }

    private data class Receipt(val matched: Boolean, val labId: UUID?, val generation: Int?, val challengeId: UUID)

    @Transactional
    fun claim(runnerId: String): GradeAssignment? {
        val candidates = jdbc.sql(
            """SELECT j.id FROM jobs j JOIN submissions s ON s.id = j.submission_id
               LEFT JOIN labs l ON l.id = (s.safe_metadata->>'labId')::uuid
               WHERE j.kind = 'GRADE' AND j.state = 'DISPATCHED' AND s.kind = 'FLAG' AND j.attempt < ? AND (l.id IS NULL OR l.runner_id = ?)
               ORDER BY j.dispatched_at, j.id LIMIT 10""",
        ).params(async.maxAttempts, runnerId).query(UUID::class.java).list().filterNotNull()
        for (candidate in candidates) {
            val lease = jobs.claimJob(candidate, runnerId) ?: continue
            return GradeAssignment(lease, task(lease))
        }
        return null
    }

    fun start(runnerId: String, lease: JobLease): Ack = if (holds(runnerId, lease)) jobs.start(lease) else stale(runnerId, lease, "start")

    fun heartbeat(runnerId: String, lease: JobLease): Ack = if (holds(runnerId, lease)) jobs.heartbeat(lease) else stale(runnerId, lease, "heartbeat")

    @Transactional
    fun observed(runnerId: String, lease: JobLease, observation: ObjectiveObservation): Ack {
        if (!holds(runnerId, lease)) return stale(runnerId, lease, "observed")
        val submission = checkNotNull(lease.submissionId)
        val receipt = receipt(submission)
        val unverified = !isolationVerified(submission, receipt?.labId)
        val digest = Digests.canonical(mapOf("jobId" to lease.jobId.toString(), "attempt" to lease.attempt, "observed" to observation.observed,
            "matchingRecords" to observation.matchingRecords, "recordsDigest" to observation.recordsDigest))
        fun report(outcome: JobOutcome, verdict: Verdict?, gates: List<GateReport>) =
            JobResultReport(outcome, verdict, digest, POLICY, fake = false, gates = gates, unverifiedIsolation = unverified)
        val result = when {
            receipt == null -> report(JobOutcome.PLATFORM_ERROR, null, emptyList())
            !receipt.matched -> report(JobOutcome.COMPLETED, Verdict.FAIL, listOf(GateReport("flag", GateResult.FAIL)))
            observation.observed == true -> report(JobOutcome.COMPLETED, Verdict.PASS, listOf(GateReport("flag", GateResult.PASS), GateReport("objective", GateResult.PASS)))
            observation.observed == false -> report(JobOutcome.INCONCLUSIVE, null, listOf(GateReport("flag", GateResult.PASS), GateReport("objective", GateResult.INCONCLUSIVE)))
            else -> report(JobOutcome.PLATFORM_ERROR, null, emptyList())
        }
        val ack = jobs.complete(lease, result)
        // 10: OBJECTIVE_CONFIRMED is server-collected evidence, recorded only when the access was actually observed.
        if (ack == Ack.ACCEPTED && receipt?.matched == true && observation.observed == true) {
            val sessionId = jdbc.sql("SELECT session_id FROM submissions WHERE id = ?").param(submission).query(UUID::class.java).single()
            ledger.append(sessionId, "OBJECTIVE_CONFIRMED", EvidenceSource.COLLECTOR, TrustLevel.OBSERVED, mapOf(
                "submissionId" to submission, "labId" to receipt.labId, "generation" to receipt.generation, "challengeId" to receipt.challengeId,
                "matchingRecords" to observation.matchingRecords, "recordsDigest" to observation.recordsDigest,
            ))
        }
        return ack
    }

    /** What to observe: only for a matched flag, on the Lab generation the flag belonged to. */
    private fun task(lease: JobLease): ObjectiveTask? {
        val submission = lease.submissionId ?: return null
        val receipt = receipt(submission) ?: return null
        if (!receipt.matched || receipt.labId == null || receipt.generation == null) return null
        val (manifest, oracle) = contentOf(submission) ?: return null
        val key = manifest["challenges"]?.values()?.firstOrNull { it["id"]?.asString() == receipt.challengeId.toString() }?.get("key")?.asString() ?: return null
        val requires = oracle?.get("verifier")?.get("requires")?.values()?.map { it.asString() } ?: emptyList()
        return ObjectiveTask(receipt.labId, receipt.generation, key, requires)
    }

    /** Public manifest and grader-only oracle of the Session's pinned version. The oracle never leaves graders. */
    private fun contentOf(submission: UUID): Pair<JsonNode, JsonNode?>? = jdbc.sql(
        """SELECT sv.public_manifest::text, sv.oracle_key FROM submissions s JOIN sessions se ON se.id = s.session_id
           JOIN scenario_versions sv ON sv.id = se.scenario_version_id WHERE s.id = ?""",
    ).param(submission).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)?.let { (manifest, oracleKey) ->
        val oracle = runCatching { store.get(oracleKey) }.getOrNull()?.let { runCatching { json.readTree(it)["oracle"] }.getOrNull() }
        json.readTree(manifest) to oracle
    }

    /** Loads and verifies the signed receipt; null when it is missing, tampered or for another submission. */
    private fun receipt(submission: UUID): Receipt? {
        val artifactId = jdbc.sql("SELECT artifact_id FROM submissions WHERE id = ?").param(submission).query(UUID::class.java).optional().orElse(null) ?: return null
        val content = artifacts.readInternal(artifactId) ?: return null
        val node = runCatching { json.readTree(content.bytes) }.getOrNull() ?: return null
        val body = node["body"] ?: return null
        val plain = json.convertValue(body, Map::class.java)
        val keyVersion = body["keyVersion"]?.asString() ?: return null
        if (!flags.verifyReceipt(CanonicalJson.encode(plain), keyVersion, node["signature"]?.asString() ?: return null)) {
            audit.record("flag receipt invalid", "receipt for submission $submission failed verification")
            return null
        }
        if (body["submissionId"]?.asString() != submission.toString()) return null
        return Receipt(
            body["matched"]?.asBoolean() == true,
            body["labId"]?.takeIf { it.isString }?.asString()?.let(UUID::fromString),
            body["generation"]?.takeIf { it.isIntegralNumber }?.asInt(),
            UUID.fromString(body["challengeId"].asString()),
        )
    }

    /** Verified only if the Lab (or, without one, every Lab of the Session) ran on verified isolation. */
    private fun isolationVerified(submission: UUID, labId: UUID?): Boolean =
        if (labId != null) {
            jdbc.sql("SELECT isolation_verified FROM labs WHERE id = ?").param(labId).query(Boolean::class.java).optional().orElse(false)
        } else {
            jdbc.sql(
                "SELECT coalesce(bool_and(l.isolation_verified), false) FROM labs l JOIN submissions s ON s.session_id = l.session_id WHERE s.id = ?",
            ).param(submission).query(Boolean::class.java).single()
        }

    internal fun holds(runnerId: String, lease: JobLease) = jobs.leaseHolder(lease) == runnerId

    internal fun stale(runnerId: String, lease: JobLease, action: String): Ack {
        audit.record("stale grade report", "rejected $action from runner $runnerId for job ${lease.jobId} token ${lease.fencingToken}")
        return Ack.STALE
    }
}
