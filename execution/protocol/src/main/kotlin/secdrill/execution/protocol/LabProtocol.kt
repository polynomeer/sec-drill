package secdrill.execution.protocol

import java.time.Instant
import java.util.UUID

enum class LabAction { PROVISION, CLEANUP }

/**
 * What a runner needs to create one Lab generation (00, 17). The runner never receives owner identity, flags,
 * oracle data or Control Plane credentials; `hardExpiresAt` lets it enforce the TTL even when Control is down.
 */
data class LabSpec(
    val labId: UUID,
    val generation: Int,
    val vcpus: Int,
    val memoryMiB: Int,
    val diskMiB: Int,
    val pids: Int,
    val hardExpiresAt: Instant,
    val allowedTargets: List<String>,
)

/** A leased PROVISION or CLEANUP job. Every report must carry the job id and current fencing token. */
data class LabAssignment(
    val jobId: UUID,
    val action: LabAction,
    val attempt: Int,
    val fencingToken: Long,
    val leaseUntil: Instant,
    val lab: LabSpec,
    /** Set for CLEANUP when a runtime was reported earlier. */
    val runtimeRef: String?,
)

/** What a provisioned runtime looks like to the runner: its reference and the internal upstream for the gateway. */
data class ProvisionedLab(val runtimeRef: String, val endpoint: String)

enum class ProvisionDecision {
    /** Lab is READY. */
    KEEP,
    /** The Lab was cancelled while provisioning (late callback): a CLEANUP job follows; the runtime must go. */
    TERMINATE,
    /** Not the current lease; the runner must stop and let reconciliation clean up. */
    STALE,
}

/** Proof of reclamation (17): which runtime objects were removed and when. */
data class CleanupReceipt(val runtimeRef: String?, val removed: List<String>, val completedAt: Instant)

/** A runtime the runner found with a valid ownership label. */
data class ObservedLab(val labId: UUID, val generation: Int, val runtimeRef: String)

/** The runner side of the Lab protocol, reached over the internal API with a workload credential (19). */
interface LabControl {
    fun claim(): LabAssignment?
    fun start(assignment: LabAssignment): Ack
    fun heartbeat(assignment: LabAssignment): Ack
    fun provisioned(assignment: LabAssignment, lab: ProvisionedLab): ProvisionDecision
    fun provisionFailed(assignment: LabAssignment, resourcesMayExist: Boolean): Ack
    fun terminated(assignment: LabAssignment, receipt: CleanupReceipt): Ack
    fun cleanupFailed(assignment: LabAssignment): Ack
    /** Reports what exists; returns the runtimes Control does not want, to be removed (orphan reconciliation). */
    fun reconcile(observed: List<ObservedLab>): List<ObservedLab>
}
