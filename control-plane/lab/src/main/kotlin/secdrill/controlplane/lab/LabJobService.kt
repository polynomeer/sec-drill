package secdrill.controlplane.lab

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.platform.AsyncProperties
import secdrill.controlplane.platform.OutboxWriter
import secdrill.controlplane.platform.SystemAudit
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.CleanupReceipt
import secdrill.execution.protocol.LabAction
import secdrill.execution.protocol.LabAssignment
import secdrill.execution.protocol.LabSpec
import secdrill.execution.protocol.ObservedLab
import secdrill.execution.protocol.ProvisionDecision
import secdrill.execution.protocol.ProvisionedLab
import secdrill.kernel.Digests
import secdrill.kernel.EventType
import secdrill.kernel.EvidenceSource
import secdrill.kernel.LabState
import secdrill.kernel.LabTerminateReason
import secdrill.kernel.Rfc3339
import secdrill.kernel.TrustLevel
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Runner side of the Lab protocol (16, 17, ADR 0007). Every call names the runner from its workload credential;
 * a runner can act only on jobs it leased, with the current fencing token, before the lease expires. CLEANUP jobs
 * go to the runner that owns the runtime. Stale calls are audited and change nothing.
 */
@Service
class LabJobService(
    private val jdbc: JdbcClient,
    private val labs: LabService,
    private val async: AsyncProperties,
    private val properties: LabProperties,
    private val ledger: LedgerAppender,
    private val outbox: OutboxWriter,
    private val audit: SystemAudit,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private fun Instant.db(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    /** Claims the next PROVISION or CLEANUP job for this runner, settling jobs whose Lab no longer wants them. */
    @Transactional
    fun claim(runnerId: String): LabAssignment? {
        repeat(10) {
            val job = jdbc.sql(
                """SELECT j.id, j.kind, j.attempt, j.fencing_token, j.lab_id FROM jobs j JOIN labs l ON l.id = j.lab_id
                   WHERE j.state = 'DISPATCHED' AND j.kind IN ('PROVISION', 'CLEANUP') AND j.attempt < ?
                     AND (j.kind = 'PROVISION' OR l.runner_id IS NULL OR l.runner_id = ?)
                   ORDER BY (j.kind = 'CLEANUP') DESC, j.dispatched_at, j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED""",
            ).params(async.maxAttempts, runnerId).query { rs, _ ->
                Claimable(rs.getObject(1, UUID::class.java), LabAction.valueOf(rs.getString(2)), rs.getInt(3), rs.getLong(4), rs.getObject(5, UUID::class.java))
            }.optional().orElse(null) ?: return null
            val lab = lockLab(job.labId)
            if (job.action == LabAction.PROVISION && lab.desired == "TERMINATED") {
                // Cancelled while waiting: never provision. A crashed earlier attempt may have left resources.
                jdbc.sql("UPDATE jobs SET state = 'CANCELLED', version = version + 1 WHERE id = ?").param(job.id).update()
                if (job.attempt > 0) {
                    jdbc.sql("UPDATE labs SET state = 'TERMINATING' WHERE id = ? AND state <> 'TERMINATING'").param(lab.id).update()
                    labs.scheduleCleanup(lab.id, lab.sessionId)
                } else {
                    labs.closeWithoutRuntime(lab.id, lab.sessionId, lab.generation, "cancelled-before-provisioning")
                }
                return@repeat
            }
            val now = now()
            val leaseUntil = now.plus(async.lease)
            jdbc.sql(
                """UPDATE jobs SET state = 'LEASED', attempt = attempt + 1, fencing_token = fencing_token + 1, worker_id = ?, lease_until = ?,
                   version = version + 1 WHERE id = ?""",
            ).params(runnerId, leaseUntil.db(), job.id).update()
            if (job.action == LabAction.PROVISION) {
                jdbc.sql("UPDATE labs SET state = CASE WHEN state = 'REQUESTED' THEN 'PROVISIONING' ELSE state END, runner_id = ? WHERE id = ?")
                    .params(runnerId, lab.id).update()
            }
            val spec = LabSpec(lab.id, lab.generation, lab.vcpus, lab.memoryMiB, lab.diskMiB, lab.pids, lab.expiresAt, lab.allowedTargets)
            return LabAssignment(job.id, job.action, job.attempt + 1, job.token + 1, leaseUntil, spec, lab.runtimeRef)
        }
        return null
    }

    @Transactional
    fun start(runnerId: String, assignment: LabAssignment): Ack = ackIf(runnerId, assignment, "start",
        jdbc.sql("UPDATE jobs SET state = 'RUNNING', version = version + 1 WHERE id = ? AND fencing_token = ? AND worker_id = ? AND state = 'LEASED' AND lease_until > ?")
            .params(assignment.jobId, assignment.fencingToken, runnerId, now().db()).update())

    @Transactional
    fun heartbeat(runnerId: String, assignment: LabAssignment): Ack = ackIf(runnerId, assignment, "heartbeat",
        jdbc.sql("UPDATE jobs SET lease_until = ? WHERE id = ? AND fencing_token = ? AND worker_id = ? AND state IN ('LEASED', 'RUNNING') AND lease_until > ?")
            .params(now().plus(async.lease).db(), assignment.jobId, assignment.fencingToken, runnerId, now().db()).update())

    @Transactional
    fun provisioned(runnerId: String, assignment: LabAssignment, result: ProvisionedLab): ProvisionDecision {
        if (!currentLease(runnerId, assignment, LabAction.PROVISION)) return ProvisionDecision.STALE.also { stale(runnerId, assignment, "provisioned") }
        val lab = lockLab(assignment.lab.labId)
        val now = now()
        finishJob(assignment.jobId, Digests.sha256Hex(result.runtimeRef.toByteArray()))
        jdbc.sql("UPDATE labs SET runtime_ref = ?, endpoint = ?, runner_id = ? WHERE id = ?").params(result.runtimeRef.take(200), result.endpoint.take(300), runnerId, lab.id).update()
        if (lab.desired == "RUNNING" && lab.state in setOf(LabState.REQUESTED, LabState.PROVISIONING)) {
            jdbc.sql("UPDATE labs SET state = 'READY', ready_at = ?, version = version + 1 WHERE id = ?").params(now.db(), lab.id).update()
            jdbc.sql("UPDATE sessions SET status = 'ACTIVE', version = version + 1 WHERE id = ? AND status = 'CREATED'").param(lab.sessionId).update()
            val evidence = ledger.append(lab.sessionId, EventType.LabReady.name, EvidenceSource.SUPERVISOR, TrustLevel.SERVER_VERIFIED,
                mapOf("labId" to lab.id, "generation" to lab.generation))
            outbox.append(EventType.LabReady, lab.id, 1, lab.sessionId, lab.id,
                mapOf("labId" to lab.id.toString(), "generation" to lab.generation, "runtimeRef" to result.runtimeRef), seq = evidence.seq)
            return ProvisionDecision.KEEP
        }
        // Late callback (13): the Lab was cancelled meanwhile. Never READY; reclaim the runtime that now exists.
        jdbc.sql("UPDATE labs SET state = 'TERMINATING', version = version + 1 WHERE id = ?").param(lab.id).update()
        labs.scheduleCleanup(lab.id, lab.sessionId)
        audit.record("lab late callback", "lab ${lab.id} generation ${lab.generation} provisioned after cancellation; cleanup scheduled")
        return ProvisionDecision.TERMINATE
    }

    @Transactional
    fun provisionFailed(runnerId: String, assignment: LabAssignment, resourcesMayExist: Boolean): Ack {
        if (!currentLease(runnerId, assignment, LabAction.PROVISION)) return stale(runnerId, assignment, "provision-failed")
        val lab = lockLab(assignment.lab.labId)
        jdbc.sql("UPDATE jobs SET state = 'FAILED', last_error = 'PLATFORM_ERROR', worker_id = NULL, lease_until = NULL, version = version + 1 WHERE id = ?")
            .param(assignment.jobId).update()
        jdbc.sql(
            """UPDATE labs SET state = 'FAILED', desired_state = 'TERMINATED', terminate_reason = coalesce(terminate_reason, 'PROVISION_FAILED'),
               terminate_requested_at = coalesce(terminate_requested_at, ?), version = version + 1 WHERE id = ?""",
        ).params(now().db(), lab.id).update()
        if (resourcesMayExist) labs.scheduleCleanup(lab.id, lab.sessionId) else labs.closeWithoutRuntime(lab.id, lab.sessionId, lab.generation, "provision-failed-without-resources")
        return Ack.ACCEPTED
    }

    @Transactional
    fun terminated(runnerId: String, assignment: LabAssignment, receipt: CleanupReceipt): Ack {
        if (!currentLease(runnerId, assignment, LabAction.CLEANUP)) return stale(runnerId, assignment, "terminated")
        val lab = lockLab(assignment.lab.labId)
        val now = now()
        val receiptJson = json.writeValueAsString(mapOf("runtimeRef" to receipt.runtimeRef, "removed" to receipt.removed, "completedAt" to Rfc3339.format(receipt.completedAt), "runnerId" to runnerId))
        finishJob(assignment.jobId, Digests.sha256Hex(receiptJson.toByteArray()))
        jdbc.sql("UPDATE labs SET state = 'TERMINATED', cleanup_confirmed_at = ?, cleanup_receipt = ?::jsonb, version = version + 1 WHERE id = ?")
            .params(now.db(), receiptJson, lab.id).update()
        val evidence = ledger.append(lab.sessionId, EventType.LabTerminated.name, EvidenceSource.SUPERVISOR, TrustLevel.SERVER_VERIFIED,
            mapOf("labId" to lab.id, "generation" to lab.generation, "receiptDigest" to Digests.sha256Hex(receiptJson.toByteArray())))
        outbox.append(EventType.LabTerminated, lab.id, 2, lab.sessionId, lab.id, mapOf(
            "labId" to lab.id.toString(), "generation" to lab.generation,
            "cleanupReceipt" to mapOf("key" to "labs/${lab.id}/receipt", "digest" to Digests.sha256Hex(receiptJson.toByteArray()), "byteSize" to receiptJson.length),
        ), seq = evidence.seq)
        return Ack.ACCEPTED
    }

    @Transactional
    fun cleanupFailed(runnerId: String, assignment: LabAssignment): Ack {
        if (!currentLease(runnerId, assignment, LabAction.CLEANUP)) return stale(runnerId, assignment, "cleanup-failed")
        jdbc.sql("UPDATE jobs SET state = 'FAILED', last_error = 'PLATFORM_ERROR', worker_id = NULL, lease_until = NULL, version = version + 1 WHERE id = ?")
            .param(assignment.jobId).update()
        // Still holds resources: counts against quota until a later cleanup succeeds (13).
        jdbc.sql("UPDATE labs SET state = 'CLEANUP_FAILED', version = version + 1 WHERE id = ?").param(assignment.lab.labId).update()
        return Ack.ACCEPTED
    }

    /**
     * Orphan reconciliation (17): runtimes the runner holds but Control does not want are returned for removal;
     * Labs Control believes are live on this runner but the runner does not hold are closed as RUNTIME_LOST.
     */
    @Transactional
    fun reconcile(runnerId: String, observed: List<ObservedLab>): List<ObservedLab> {
        val wanted = jdbc.sql(
            "SELECT id, generation FROM labs WHERE runner_id = ? AND desired_state = 'RUNNING' AND state IN ('PROVISIONING', 'READY')",
        ).param(runnerId).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getInt(2) }.list().toSet()
        val inFlight = jdbc.sql("SELECT lab_id FROM jobs WHERE worker_id = ? AND state IN ('LEASED', 'RUNNING') AND lab_id IS NOT NULL")
            .param(runnerId).query(UUID::class.java).list().filterNotNull().toSet()
        val unwanted = observed.filter { (it.labId to it.generation) !in wanted && it.labId !in inFlight }
        unwanted.forEach { audit.record("lab orphan", "runner $runnerId holds unwanted runtime for lab ${it.labId} generation ${it.generation}") }
        val held = observed.map { it.labId to it.generation }.toSet()
        (wanted - held).filter { it.first !in inFlight }.forEach { (labId, generation) ->
            val lab = lockLab(labId)
            if (lab.state == LabState.READY) {
                jdbc.sql("UPDATE labs SET desired_state = 'TERMINATED', terminate_reason = 'RUNTIME_LOST', terminate_requested_at = ?, state = 'TERMINATING' WHERE id = ?").params(now().db(), labId).update()
                labs.closeWithoutRuntime(labId, lab.sessionId, generation, "runtime-absent-on-reconcile")
                audit.record("lab runtime lost", "lab $labId generation $generation not found on runner $runnerId")
            }
        }
        return unwanted
    }

    /** Gateway view of one Lab (17): checked on every proxied request. */
    fun gatewayView(labId: UUID): Map<String, Any?>? = jdbc.sql(
        "SELECT generation, owner_id, state, desired_state, endpoint, expires_at FROM labs WHERE id = ?",
    ).param(labId).query { rs, _ ->
        mapOf(
            "labId" to labId.toString(), "generation" to rs.getInt(1), "ownerId" to rs.getObject(2, UUID::class.java).toString(),
            "ready" to (rs.getString(3) == "READY" && rs.getString(4) == "RUNNING"), "endpoint" to rs.getString(5),
            "expiresAt" to Rfc3339.format(rs.getObject(6, OffsetDateTime::class.java).toInstant()),
        )
    }.optional().orElse(null)

    /** Gateway activity extends the idle TTL, never the hard TTL. */
    @Transactional
    fun touch(labId: UUID) {
        val now = now()
        jdbc.sql("UPDATE labs SET idle_expires_at = LEAST(?, expires_at) WHERE id = ? AND state = 'READY' AND desired_state = 'RUNNING'")
            .params(now.plus(properties.idleTtl).db(), labId).update()
    }

    @Scheduled(fixedDelayString = "\${secdrill.async.sweep-interval:1s}")
    fun scheduledSweep() {
        if (async.schedulingEnabled) sweepOnce()
    }

    /** TTL enforcement, cleanup retries and stuck-provisioning recovery (17: 1-minute sweeper; here every sweep). */
    @Transactional
    fun sweepOnce(): Int {
        val now = now()
        var changed = 0
        jdbc.sql("SELECT id, idle_expires_at <= ?, expires_at <= ? FROM labs WHERE desired_state = 'RUNNING' AND (idle_expires_at <= ? OR expires_at <= ?)")
            .params(now.db(), now.db(), now.db(), now.db())
            .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getBoolean(2), rs.getBoolean(3)) }.list()
            .forEach { (id, _, hard) -> labs.requestTermination(id, if (hard) LabTerminateReason.HARD_TTL else LabTerminateReason.IDLE_TTL); changed++ }
        // Provisioning that exhausted its attempts (lease expiries): the runtime may exist, so fail and reclaim.
        jdbc.sql(
            """SELECT l.id FROM labs l WHERE l.desired_state = 'RUNNING' AND l.state IN ('REQUESTED', 'PROVISIONING')
               AND EXISTS (SELECT 1 FROM jobs j WHERE j.lab_id = l.id AND j.kind = 'PROVISION' AND j.state = 'FAILED')
               AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.lab_id = l.id AND j.kind = 'PROVISION' AND j.state NOT IN ('FAILED', 'CANCELLED', 'SUCCEEDED'))""",
        ).query(UUID::class.java).list().filterNotNull().forEach { id ->
            val lab = lockLab(id)
            jdbc.sql(
                """UPDATE labs SET state = 'FAILED', desired_state = 'TERMINATED', terminate_reason = 'PROVISION_FAILED', terminate_requested_at = ?,
                   version = version + 1 WHERE id = ?""",
            ).params(now.db(), id).update()
            labs.scheduleCleanup(id, lab.sessionId)
            changed++
        }
        // Cleanup that failed, or TERMINATING without a live cleanup job, gets a new attempt after a delay.
        jdbc.sql(
            """SELECT l.id, l.session_id FROM labs l WHERE l.cleanup_confirmed_at IS NULL AND l.desired_state = 'TERMINATED'
               AND l.state IN ('CLEANUP_FAILED', 'TERMINATING', 'FAILED')
               AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.lab_id = l.id AND j.state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED'))
               AND coalesce((SELECT max(j.created_at) FROM jobs j WHERE j.lab_id = l.id AND j.kind = 'CLEANUP'), l.terminate_requested_at) <= ?""",
        ).param(now.minus(properties.cleanupRetryDelay).db()).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getObject(2, UUID::class.java) }.list()
            .forEach { (lab, session) -> labs.scheduleCleanup(lab, session); changed++ }
        jdbc.sql(
            """SELECT id FROM labs WHERE cleanup_confirmed_at IS NULL AND terminate_requested_at <= ?
               AND NOT EXISTS (SELECT 1 FROM audit_events a WHERE a.purpose = 'lab cleanup overdue' AND a.action LIKE '%' || labs.id::text || '%')""",
        ).param(now.minus(properties.cleanupAlertAfter).db()).query(UUID::class.java).list().filterNotNull()
            .forEach { audit.record("lab cleanup overdue", "lab $it not reclaimed within ${properties.cleanupAlertAfter}") }
        return changed
    }

    private fun currentLease(runnerId: String, assignment: LabAssignment, action: LabAction): Boolean =
        jdbc.sql(
            """SELECT id FROM jobs WHERE id = ? AND kind = ? AND fencing_token = ? AND worker_id = ? AND state = 'RUNNING' AND lease_until > ?
               AND lab_id = ? FOR UPDATE""",
        ).params(assignment.jobId, action.name, assignment.fencingToken, runnerId, now().db(), assignment.lab.labId).query(UUID::class.java).optional().isPresent

    private fun finishJob(jobId: UUID, resultDigest: String) {
        jdbc.sql("UPDATE jobs SET state = 'SUCCEEDED', result_digest = ?, worker_id = NULL, lease_until = NULL, version = version + 1 WHERE id = ?")
            .params(resultDigest, jobId).update()
    }

    private fun ackIf(runnerId: String, assignment: LabAssignment, action: String, updated: Int) =
        if (updated == 1) Ack.ACCEPTED else stale(runnerId, assignment, action)

    private fun stale(runnerId: String, assignment: LabAssignment, action: String): Ack {
        audit.record("stale lab report", "rejected $action from runner $runnerId for job ${assignment.jobId} token ${assignment.fencingToken}")
        return Ack.STALE
    }

    private fun lockLab(id: UUID): LockedLab = jdbc.sql(
        """SELECT l.id, l.session_id, l.generation, l.state, l.desired_state, l.runtime_ref, l.expires_at, sv.public_manifest::text
           FROM labs l JOIN sessions s ON s.id = l.session_id JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE l.id = ? FOR UPDATE OF l""",
    ).param(id).query { rs, _ ->
        val manifest = json.readTree(rs.getString(8))
        val targets = manifest["scope"]?.get("allowedTargets")?.values()?.map { it.asString() } ?: emptyList()
        // Manifest limits, never above the 00 defaults the content schema also caps.
        fun limit(name: String, max: Int) = (manifest["runtime"]?.get(name)?.asInt() ?: max).coerceIn(1, max)
        LockedLab(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getInt(3), LabState.valueOf(rs.getString(4)),
            rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime::class.java).toInstant(), targets,
            limit("vcpus", 2), limit("memoryMiB", 2048), limit("diskMiB", 4096), limit("pids", 256))
    }.single()

    private data class Claimable(val id: UUID, val action: LabAction, val attempt: Int, val token: Long, val labId: UUID)

    private data class LockedLab(
        val id: UUID, val sessionId: UUID, val generation: Int, val state: LabState, val desired: String,
        val runtimeRef: String?, val expiresAt: Instant, val allowedTargets: List<String>,
        val vcpus: Int, val memoryMiB: Int, val diskMiB: Int, val pids: Int,
    )
}
