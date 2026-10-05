package secdrill.execution.protocol

import secdrill.kernel.JobKind
import secdrill.kernel.Verdict
import java.time.Instant
import java.util.UUID

/**
 * A claimed job. `fencingToken` increases on every claim; the Control Plane accepts heartbeats and results only
 * for the current token while the lease is unexpired (13, 16, ADR-004).
 */
data class JobLease(
    val jobId: UUID,
    val kind: JobKind,
    val attempt: Int,
    val fencingToken: Long,
    val leaseUntil: Instant,
    val submissionId: UUID?,
    /** Digest of the canonical submission request; the content itself is not part of the protocol yet (T07–T09). */
    val bundleDigest: String?,
)

enum class JobOutcome {
    /** The grader ran to completion. `verdict` is PASS or FAIL (a user resource limit is FAIL, 20). */
    COMPLETED,
    /** Host, store or collector trouble. Retried up to three attempts, then SYSTEM_ERROR, never FAIL (00). */
    PLATFORM_ERROR,
    /** Signature mismatch or invalid content. SYSTEM_ERROR without automatic retry (20). */
    CONTENT_INVALID,
    /**
     * The grader ran but could not confirm the objective independently (09: a correct flag whose target access was
     * not observed). SYSTEM_ERROR with an INCONCLUSIVE gate, no retry, never FAIL.
     */
    INCONCLUSIVE,
}

/** One gate of an evaluation (OpenAPI `Evaluation.gates`). */
data class GateReport(val key: String, val result: secdrill.kernel.GateResult)

data class JobResultReport(
    val outcome: JobOutcome,
    val verdict: Verdict?,
    val resultDigest: String,
    /** Grading policy that produced the verdict, e.g. `fake-worker/0`. Fake results must say so here. */
    val policyVersion: String,
    /** True when no real execution happened. Stored with the evaluation and shown as unverified. */
    val fake: Boolean,
    val gates: List<GateReport> = emptyList(),
    /** Ran on a runtime without verified isolation; the result is a demo result (prompt 08). */
    val unverifiedIsolation: Boolean = false,
) {
    init {
        require(outcome != JobOutcome.COMPLETED || verdict == Verdict.PASS || verdict == Verdict.FAIL) {
            "a completed job reports PASS or FAIL"
        }
        require(outcome == JobOutcome.COMPLETED || verdict == null) { "only completed jobs carry a verdict" }
    }
}

enum class Ack {
    ACCEPTED,
    /** Not the current lease (expired, reassigned, cancelled or wrong token). The worker must stop. */
    STALE,
}

/** What a worker may do. Implemented by the Control Plane; reached in-process now, over mTLS ingest later (T06). */
interface JobControl {
    fun claimNext(workerId: String, kinds: Set<JobKind>): JobLease?
    fun start(lease: JobLease): Ack
    fun heartbeat(lease: JobLease): Ack
    fun complete(lease: JobLease, report: JobResultReport): Ack
}

/**
 * What the runner hosting a Lab is asked to observe for a FLAG submission (09, 20). It is not told whether the flag
 * matched; the Control Plane decides the verdict from its own signed receipt and this observation.
 */
data class ObjectiveTask(val labId: UUID, val generation: Int, val challengeKey: String, val requires: List<String>)

/** A GRADE job for a runner: the lease plus, for FLAG submissions, the objective to observe (null: no live Lab). */
data class GradeAssignment(val lease: JobLease, val objective: ObjectiveTask?)

/**
 * Independent observation of the target (supervisor-side access records, not learner output).
 * `observed` is null when the records could not be collected (platform error), false when they were collected and
 * show no qualifying access.
 */
data class ObjectiveObservation(val observed: Boolean?, val matchingRecords: Int, val recordsDigest: String?)

/** Runner side of grading over the internal API (AGENT credential). */
interface GradeControl {
    fun claim(): GradeAssignment?
    fun start(lease: JobLease): Ack
    fun heartbeat(lease: JobLease): Ack
    fun observed(lease: JobLease, observation: ObjectiveObservation): Ack
}
