package secdrill.execution.fake

import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.JobControl
import secdrill.execution.protocol.JobLease
import secdrill.execution.protocol.JobOutcome
import secdrill.execution.protocol.JobResultReport
import secdrill.kernel.Digests
import secdrill.kernel.JobKind
import secdrill.kernel.Verdict

/**
 * Development stand-in for a grading runner. It never runs learner code and never inspects content, so every
 * report it sends is marked `fake = true` with policy `fake-worker/0`. Its default verdict is FAIL so a fake can
 * never grant success by accident. Enabled only in local and test setups (FakeWorkerSafetyCheck).
 */
class FakeGradingWorker(
    private val control: JobControl,
    private val workerId: String = "fake-worker",
    /** Decides the fake outcome per lease; tests script platform errors and verdicts with it. */
    private val behavior: (JobLease) -> FakeStep = { FakeStep.Complete(Verdict.FAIL) },
) {
    companion object {
        const val POLICY = "fake-worker/0"
    }

    sealed interface FakeStep {
        data class Complete(val verdict: Verdict) : FakeStep
        data object PlatformError : FakeStep
        data object ContentInvalid : FakeStep
        /** Simulates a crashed worker: claims and starts, then goes silent so the lease expires. */
        data object Abandon : FakeStep
    }

    /** Processes at most one job. Returns the lease it worked on, or null when nothing was claimable. */
    fun runOnce(): JobLease? {
        val lease = control.claimNext(workerId, setOf(JobKind.GRADE)) ?: return null
        if (control.start(lease) == Ack.STALE) return lease
        val step = behavior(lease)
        if (step == FakeStep.Abandon) return lease
        if (control.heartbeat(lease) == Ack.STALE) return lease
        control.complete(lease, report(lease, step))
        return lease
    }

    fun report(lease: JobLease, step: FakeStep): JobResultReport {
        val digest = Digests.canonical(mapOf("jobId" to lease.jobId, "attempt" to lease.attempt, "fake" to true, "step" to step.toString()))
        return when (step) {
            is FakeStep.Complete -> JobResultReport(JobOutcome.COMPLETED, step.verdict, digest, POLICY, fake = true)
            FakeStep.PlatformError -> JobResultReport(JobOutcome.PLATFORM_ERROR, null, digest, POLICY, fake = true)
            FakeStep.ContentInvalid -> JobResultReport(JobOutcome.CONTENT_INVALID, null, digest, POLICY, fake = true)
            FakeStep.Abandon -> error("abandoned jobs send no report")
        }
    }
}
