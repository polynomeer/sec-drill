package secdrill.execution.agent

import secdrill.execution.protocol.CleanupReceipt
import secdrill.execution.protocol.LabSpec
import secdrill.execution.protocol.ObservedLab
import secdrill.execution.protocol.ProvisionedLab
import java.time.Instant
import java.util.UUID

/** A Lab runtime this runner can prove it created (labels plus a valid ownership signature). */
data class OwnedRuntime(val labId: UUID, val generation: Int, val runtimeRef: String, val hardExpiresAt: Instant) {
    fun observed() = ObservedLab(labId, generation, runtimeRef)
}

data class ExecResult(val exitCode: Int, val output: String, val truncated: Boolean)

/**
 * Runtime adapter (11, 17). The isolation profile is part of the adapter, so callers cannot weaken it:
 * there is no flag for privileged mode, host mounts, devices or extra networks.
 */
interface RuntimeAdapter {
    /** Isolation profile name shown in every report: `local-trusted` or `lab-strong`. */
    val profile: String
    fun provision(spec: LabSpec): ProvisionedLab
    /** Removes only runtimes whose ownership label verifies; returns what was removed. Idempotent. */
    fun terminate(labId: UUID, generation: Int): CleanupReceipt
    fun list(): List<OwnedRuntime>
    /** Runs a probe inside the Lab with the 1 MiB output cap (17); used by isolation checks and graders. */
    fun exec(labId: UUID, generation: Int, argv: List<String>): ExecResult
}
