package secdrill.controlplane.lab

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.ResourceNotFoundException
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.identity.OperatorPrincipal
import secdrill.controlplane.identity.WorkloadPrincipal
import secdrill.execution.protocol.CleanupReceipt
import secdrill.execution.protocol.LabAssignment
import secdrill.execution.protocol.ObservedLab
import secdrill.execution.protocol.ProvisionedLab
import secdrill.kernel.ApiException
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.kernel.LabTerminateReason
import secdrill.kernel.OperatorRole
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode
import java.util.UUID

/** Learner Lab routes (15). Origin and CSRF come from the identity filters. */
@RestController
class LabController(private val labs: LabService) {
    private fun expectedVersion(body: JsonNode): Long = body["expectedVersion"]?.takeIf { it.isIntegralNumber && it.asLong() >= 0 }?.asLong()
        ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "expectedVersion must be a non-negative integer")

    @PostMapping("/v1/sessions/{id}/labs")
    fun request(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<String> {
        val key = idempotencyKey?.let { runCatching { Uuids.parse(it) }.getOrNull() }
            ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key header must be a UUID")
        val version = expectedVersion(body)
        val (status, response) = labs.requestLab(principal, id, key, version, Digests.canonical(mapOf("expectedVersion" to version)))
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(response)
    }

    @PostMapping("/v1/sessions/{id}/stop")
    fun stop(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @RequestBody body: JsonNode) =
        ResponseEntity.accepted().body(labs.stopSession(principal, id, expectedVersion(body)))

    @PostMapping("/v1/sessions/{id}/labs/{labId}/connect")
    fun connect(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID, @PathVariable labId: UUID) =
        labs.connect(principal, id, labId)
}

data class AssignmentBody(val assignment: LabAssignment)
data class ProvisionedBody(val assignment: LabAssignment, val lab: ProvisionedLab)
data class FailedBody(val assignment: LabAssignment, val resourcesMayExist: Boolean)
data class TerminatedBody(val assignment: LabAssignment, val receipt: CleanupReceipt)
data class ReconcileBody(val observed: List<ObservedLab>)

/**
 * Internal runner API (15 `/internal/jobs/{id}/claim`, `.../result`; ADR 0007). Identifiers in bodies are only
 * lookup keys: lease ownership, token and state are re-checked from the database on every call.
 */
@RestController
class LabInternalController(private val jobs: LabJobService) {
    @PostMapping("/internal/v1/lab-jobs/claim")
    fun claim(@AuthenticationPrincipal runner: WorkloadPrincipal): ResponseEntity<LabAssignment> =
        jobs.claim(runner.runnerId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @PostMapping("/internal/v1/lab-jobs/start")
    fun start(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: AssignmentBody) = mapOf("ack" to jobs.start(runner.runnerId, body.assignment))

    @PostMapping("/internal/v1/lab-jobs/heartbeat")
    fun heartbeat(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: AssignmentBody) = mapOf("ack" to jobs.heartbeat(runner.runnerId, body.assignment))

    @PostMapping("/internal/v1/lab-jobs/provisioned")
    fun provisioned(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: ProvisionedBody) =
        mapOf("decision" to jobs.provisioned(runner.runnerId, body.assignment, body.lab))

    @PostMapping("/internal/v1/lab-jobs/provision-failed")
    fun provisionFailed(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: FailedBody) =
        mapOf("ack" to jobs.provisionFailed(runner.runnerId, body.assignment, body.resourcesMayExist))

    @PostMapping("/internal/v1/lab-jobs/terminated")
    fun terminated(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: TerminatedBody) =
        mapOf("ack" to jobs.terminated(runner.runnerId, body.assignment, body.receipt))

    @PostMapping("/internal/v1/lab-jobs/cleanup-failed")
    fun cleanupFailed(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: AssignmentBody) =
        mapOf("ack" to jobs.cleanupFailed(runner.runnerId, body.assignment))

    @PostMapping("/internal/v1/labs/reconcile")
    fun reconcile(@AuthenticationPrincipal runner: WorkloadPrincipal, @RequestBody body: ReconcileBody) =
        mapOf("terminate" to jobs.reconcile(runner.runnerId, body.observed))

    @GetMapping("/internal/v1/gateway/labs/{labId}")
    fun gatewayView(@PathVariable labId: UUID) = jobs.gatewayView(labId) ?: throw ResourceNotFoundException()

    @PostMapping("/internal/v1/gateway/labs/{labId}/activity")
    fun activity(@PathVariable labId: UUID): ResponseEntity<Void> {
        jobs.touch(labId)
        return ResponseEntity.noContent().build()
    }
}

/** Operator Lab stop and pool drain (FR-10, 25). */
@RestController
class LabOpsController(private val labs: LabService) {
    @PostMapping("/ops/v1/labs/{labId}/stop")
    fun stop(@AuthenticationPrincipal principal: OperatorPrincipal, @PathVariable labId: UUID): ResponseEntity<Void> {
        if (principal.role !in setOf(OperatorRole.OPERATOR, OperatorRole.SECURITY_ADMIN)) throw ApiException(ErrorCode.FORBIDDEN, "Role is not allowed to stop Labs")
        labs.requestTermination(labId, LabTerminateReason.OPERATOR)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/ops/v1/lab-pool/drain", consumes = [org.springframework.http.MediaType.APPLICATION_JSON_VALUE])
    fun drain(@AuthenticationPrincipal principal: OperatorPrincipal, @RequestBody body: tools.jackson.databind.JsonNode): ResponseEntity<Void> {
        if (principal.role !in setOf(OperatorRole.OPERATOR, OperatorRole.SECURITY_ADMIN)) throw ApiException(ErrorCode.FORBIDDEN, "Role is not allowed to drain the Lab pool")
        val draining = body["draining"]?.takeIf { it.isBoolean }?.asBoolean()
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "draining must be a boolean")
        labs.setDraining(draining)
        return ResponseEntity.noContent().build()
    }
}
