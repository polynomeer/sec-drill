package secdrill.controlplane.privacy

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.access.ResourceNotFoundException
import secdrill.controlplane.evidence.ArtifactStore
import secdrill.controlplane.evidence.LocalArtifactStore
import secdrill.controlplane.identity.AuthSessionService
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.identity.OperatorPrincipal
import secdrill.controlplane.lab.LabService
import secdrill.controlplane.platform.AsyncProperties
import secdrill.controlplane.platform.IdempotencyDecision
import secdrill.controlplane.platform.IdempotencyStore
import secdrill.controlplane.platform.SystemAudit
import secdrill.kernel.ApiException
import secdrill.kernel.AuthRevokeReason
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.kernel.LabTerminateReason
import secdrill.kernel.OperatorRole
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

@ConfigurationProperties("secdrill.privacy")
data class PrivacyProperties(
    /** How long an unreferenced object must sit before the orphan sweep removes it, so in-flight writes survive. */
    val orphanGrace: Duration = Duration.ofHours(1),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrivacyProperties::class)
class PrivacyConfig

/** OpenAPI `AsyncReceipt` (15): shared by exports and deletions, polled at `/v1/async-jobs/{id}`. */
data class AsyncReceipt(val id: UUID, val status: String, val pollPath: String, val downloadPath: String? = null)

/** `DeletionRequest` body (15). `additionalProperties:false`; the oneOf is enforced in [parse]. */
data class DeletionBody(val scope: String, val sessionId: UUID?, val confirmationToken: String) {
    companion object {
        fun parse(node: JsonNode): DeletionBody {
            if (!node.isObject || (node.propertyNames().toSet() - setOf("scope", "sessionId", "confirmationToken")).isNotEmpty()) {
                throw ApiException(ErrorCode.VALIDATION_FAILED, "Only scope, sessionId and confirmationToken are allowed")
            }
            val scope = node["scope"]?.takeIf { it.isString }?.asString()?.takeIf { it in setOf("ACCOUNT", "SESSION") }
                ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "scope must be ACCOUNT or SESSION")
            val token = node["confirmationToken"]?.takeIf { it.isString }?.asString()?.takeIf { it.length in 16..256 }
                ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "confirmationToken must be 16-256 characters")
            val session = node["sessionId"]?.takeIf { it.isString }?.asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (scope == "SESSION" && session == null) throw ApiException(ErrorCode.VALIDATION_FAILED, "SESSION scope needs a sessionId")
            if (scope == "ACCOUNT" && session != null) throw ApiException(ErrorCode.VALIDATION_FAILED, "ACCOUNT scope must not name a sessionId")
            return DeletionBody(scope, session, token)
        }
    }
}

private fun Clock.nowMicros() = instant().truncatedTo(ChronoUnit.MICROS)
private fun java.time.Instant.db(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/**
 * Data export (15, 25, ADR 0013). The learner's own sessions, submissions, active evaluations and ledger safe
 * payloads are assembled into one private object and polled like an async job. Oracle, flags and hidden tests are
 * never read, so nothing grader-only can reach the export.
 */
@Service
class ExportService(
    private val jdbc: JdbcClient,
    private val store: ArtifactStore,
    private val guard: OwnershipGuard,
    private val async: AsyncProperties,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    fun request(principal: LearnerPrincipal, sessionId: UUID?): AsyncReceipt {
        sessionId?.let { guard.requireOwned(principal, OwnedResource.SESSION, it) }
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO export_jobs(id, owner_id, session_id, status, requested_at) VALUES (?, ?, ?, 'ACCEPTED', ?)")
            .params(listOf(id, principal.userId.value, sessionId, clock.nowMicros().db())).update()
        return AsyncReceipt(id, "ACCEPTED", "/v1/async-jobs/$id")
    }

    @Scheduled(fixedDelayString = "\${secdrill.async.sweep-interval:1s}")
    fun scheduled() {
        if (async.schedulingEnabled) while (runOnce() != null) Unit
    }

    /** Assembles at most one ACCEPTED export; returns its id or null when none was waiting. */
    @Transactional
    fun runOnce(): UUID? {
        val job = jdbc.sql("SELECT id, owner_id, session_id FROM export_jobs WHERE status = 'ACCEPTED' ORDER BY requested_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
            .query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getObject(3, UUID::class.java)) }
            .optional().orElse(null) ?: return null
        jdbc.sql("UPDATE export_jobs SET status = 'RUNNING' WHERE id = ?").param(job.first).update()
        val bytes = json.writeValueAsBytes(assemble(job.second, job.third))
        val key = "exports/${job.first}"
        store.put(key, bytes)
        jdbc.sql("UPDATE export_jobs SET status = 'COMPLETED', object_key = ?, digest = ?, byte_size = ?, completed_at = ? WHERE id = ?")
            .params(key, Digests.sha256Hex(bytes), bytes.size.toLong(), clock.nowMicros().db(), job.first).update()
        return job.first
    }

    private fun assemble(owner: UUID, sessionId: UUID?): Map<String, Any?> {
        val query = if (sessionId == null) {
            jdbc.sql("SELECT id, mode, status, phase, created_at FROM sessions WHERE owner_id = ? ORDER BY created_at, id").param(owner)
        } else {
            jdbc.sql("SELECT id, mode, status, phase, created_at FROM sessions WHERE owner_id = ? AND id = ? ORDER BY created_at, id").params(owner, sessionId)
        }
        val sessions = query.query { rs, _ ->
            val session = rs.getObject(1, UUID::class.java)
            mapOf(
                "id" to session.toString(), "mode" to rs.getString(2), "status" to rs.getString(3), "phase" to rs.getString(4),
                "createdAt" to rs.getObject(5, OffsetDateTime::class.java).toInstant().toString(),
                "submissions" to submissions(session), "evidence" to evidence(session),
            )
        }.list()
        return mapOf("format" to "secdrill-export/1", "exportedAt" to clock.instant().toString(), "scope" to (sessionId?.let { "SESSION" } ?: "ACCOUNT"), "sessions" to sessions)
    }

    private fun submissions(session: UUID) = jdbc.sql(
        """SELECT s.id, s.kind, s.status, s.created_at,
                  e.verdict, e.revision, e.policy_version FROM submissions s
           LEFT JOIN evaluations e ON e.submission_id = s.id AND e.is_active
           WHERE s.session_id = ? ORDER BY s.created_at, s.id""",
    ).param(session).query { rs, _ ->
        mapOf("id" to rs.getObject(1, UUID::class.java).toString(), "kind" to rs.getString(2), "status" to rs.getString(3),
            "createdAt" to rs.getObject(4, OffsetDateTime::class.java).toInstant().toString(),
            "evaluation" to rs.getString(5)?.let { mapOf("verdict" to it, "revision" to rs.getInt(6), "policyVersion" to rs.getString(7)) })
    }.list()

    private fun evidence(session: UUID) = jdbc.sql(
        "SELECT seq, event_type, trust_level, occurred_at, safe_payload::text FROM evidence WHERE session_id = ? ORDER BY seq",
    ).param(session).query { rs, _ ->
        mapOf("seq" to rs.getLong(1), "type" to rs.getString(2), "trustLevel" to rs.getString(3),
            "occurredAt" to rs.getObject(4, OffsetDateTime::class.java).toInstant().toString(), "summary" to json.readTree(rs.getString(5)))
    }.list()

    /** Owner-checked bytes of a completed export; 404 for anyone else, a missing job or a digest mismatch. */
    fun download(principal: LearnerPrincipal, id: UUID): ByteArray {
        val row = jdbc.sql("SELECT object_key, digest FROM export_jobs WHERE id = ? AND owner_id = ? AND status = 'COMPLETED'")
            .params(id, principal.userId.value).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
            ?: throw ResourceNotFoundException()
        val bytes = store.get(row.first) ?: throw ResourceNotFoundException()
        if (Digests.sha256Hex(bytes) != row.second) throw ApiException(ErrorCode.INTERNAL_ERROR, "Internal error")
        return bytes
    }

    /** AsyncReceipt for an export the caller owns, or null if the id is not their export. */
    fun receipt(principal: LearnerPrincipal, id: UUID): AsyncReceipt? = jdbc.sql(
        "SELECT status FROM export_jobs WHERE id = ? AND owner_id = ?",
    ).params(id, principal.userId.value).query(String::class.java).optional().orElse(null)?.let { status ->
        AsyncReceipt(id, status, "/v1/async-jobs/$id", if (status == "COMPLETED") "/v1/exports/$id/download" else null)
    }
}

/**
 * Deletion request and decision (15, 19, 25, ADR 0013). A learner re-authenticates and requests; a SECURITY_ADMIN
 * who is not the requester approves or rejects. Execution is a separate step ([PrivacyEraser]).
 */
@Service
class DeletionService(
    private val jdbc: JdbcClient,
    private val guard: OwnershipGuard,
    private val idempotency: IdempotencyStore,
    private val audit: SystemAudit,
    private val clock: Clock,
    private val json: JsonMapper,
) {
    @Transactional
    fun request(principal: LearnerPrincipal, key: UUID, body: DeletionBody): AsyncReceipt {
        val owner = principal.userId.value
        val route = "POST /v1/deletion-requests"
        val digest = Digests.canonical(mapOf("scope" to body.scope, "sessionId" to body.sessionId?.toString()))
        when (val decision = idempotency.begin(owner, route, key, digest)) {
            is IdempotencyDecision.Replay -> return json.readTree(decision.body).let {
                AsyncReceipt(UUID.fromString(it["id"].asString()), it["status"].asString(), it["pollPath"].asString(),
                    it["downloadPath"]?.takeIf { node -> node.isString }?.asString())
            }
            IdempotencyDecision.Conflict -> throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key was used with a different request")
            IdempotencyDecision.Proceed -> Unit
        }
        if (!principal.confirms(body.confirmationToken)) throw ApiException(ErrorCode.FORBIDDEN, "Re-authentication failed")
        body.sessionId?.let { guard.requireOwned(principal, OwnedResource.SESSION, it) }
        val id = UUID.randomUUID()
        jdbc.sql("INSERT INTO deletion_requests(id, owner_id, scope, session_id, status, requested_at) VALUES (?, ?, ?, ?, 'REQUESTED', ?)")
            .params(listOf(id, owner, body.scope, body.sessionId, clock.nowMicros().db())).update()
        val receipt = AsyncReceipt(id, "RUNNING", "/v1/async-jobs/$id")
        val bodyJson = json.writeValueAsString(receipt)
        idempotency.record(owner, route, key, digest, 202, bodyJson)
        return receipt
    }

    /** SECURITY_ADMIN decision; a REQUESTED request becomes APPROVED or REJECTED, recorded with the deciding operator. */
    @Transactional
    fun decide(operator: OperatorPrincipal, id: UUID, approve: Boolean) {
        if (operator.role != OperatorRole.SECURITY_ADMIN) throw ApiException(ErrorCode.FORBIDDEN, "Role is not allowed to decide deletions")
        val owner = jdbc.sql("SELECT owner_id FROM deletion_requests WHERE id = ? AND status = 'REQUESTED' FOR UPDATE")
            .param(id).query(UUID::class.java).optional().orElseThrow { ResourceNotFoundException() }
        if (operator.operatorId == owner) throw ApiException(ErrorCode.FORBIDDEN, "The requester cannot approve their own deletion")
        val status = if (approve) "APPROVED" else "REJECTED"
        jdbc.sql("UPDATE deletion_requests SET status = ?, decided_at = ?, decided_by = ? WHERE id = ?")
            .params(status, clock.nowMicros().db(), operator.operatorId, id).update()
        audit.record("deletion decision", "operator ${operator.operatorId} set request $id to $status")
    }

    fun receipt(principal: LearnerPrincipal, id: UUID): AsyncReceipt? = jdbc.sql(
        "SELECT status FROM deletion_requests WHERE id = ? AND owner_id = ?",
    ).params(id, principal.userId.value).query(String::class.java).optional().orElse(null)?.let { status ->
        AsyncReceipt(id, when (status) { "COMPLETED" -> "COMPLETED"; "REJECTED" -> "FAILED"; else -> "RUNNING" }, "/v1/async-jobs/$id")
    }
}

/**
 * Erasure executor (14, 25, ADR 0013). Revokes access, terminates the subject's Labs, then runs the dedicated
 * `erase_deletion_request` function (identity linkage, artifact rows, projections, tombstones, receipt) and purges
 * the returned object bytes from the store. The append-only ledger rows are left intact.
 */
@Service
class PrivacyEraser(
    private val jdbc: JdbcClient,
    private val store: ArtifactStore,
    private val labs: LabService,
    private val auth: AuthSessionService,
    private val audit: SystemAudit,
    private val async: AsyncProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${secdrill.async.sweep-interval:1s}")
    fun scheduled() {
        if (async.schedulingEnabled) while (runOnce() != null) Unit
    }

    /** Executes at most one APPROVED request; returns its id or null when none was waiting. */
    fun runOnce(): UUID? {
        val request = jdbc.sql(
            "SELECT id, owner_id, scope, session_id FROM deletion_requests WHERE status = 'APPROVED' ORDER BY decided_at, id LIMIT 1",
        ).query { rs, _ -> Request(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getString(3), rs.getObject(4, UUID::class.java)) }
            .optional().orElse(null) ?: return null
        execute(request)
        return request.id
    }

    private data class Request(val id: UUID, val owner: UUID, val scope: String, val session: UUID?)

    private fun execute(request: Request) {
        // Revoke access and tear down live Labs first (25), so no runtime keeps serving the subject's data.
        if (request.scope == "ACCOUNT") {
            jdbc.sql("SELECT id FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL").param(request.owner).query(UUID::class.java).list()
                .filterNotNull().forEach { auth.revoke(it, AuthRevokeReason.OPERATOR) }
        }
        labTargets(request).forEach { labs.requestTermination(it, LabTerminateReason.OPERATOR) }

        val now = clock.nowMicros()
        val receipt = Digests.canonical(mapOf("deletionRequest" to request.id.toString(), "scope" to request.scope,
            "sessionId" to request.session?.toString(), "completedAt" to now.toString()))
        val keys = jdbc.sql("SELECT erase_deletion_request(?, ?, ?)").params(request.id, receipt, now.db()).query(String::class.java).list().filterNotNull()
        keys.forEach { runCatching { store.delete(it) } }
        audit.record("privacy erasure", "request ${request.id} scope ${request.scope} purged ${keys.size} objects, receipt $receipt")
    }

    private fun labTargets(request: Request): List<UUID> = jdbc.sql(
        """SELECT l.id FROM labs l WHERE l.cleanup_confirmed_at IS NULL
           AND ((? = 'ACCOUNT' AND l.session_id IN (SELECT id FROM sessions WHERE owner_id = ?)) OR (? = 'SESSION' AND l.session_id = ?))""",
    ).params(listOf(request.scope, request.owner, request.scope, request.session)).query(UUID::class.java).list().filterNotNull()

    /** Re-applies recorded tombstones after a restore (25) and purges any object bytes that came back. */
    fun reapplyTombstones(): Int {
        val keys = jdbc.sql("SELECT reapply_tombstones()").query(String::class.java).list().filterNotNull()
        keys.forEach { runCatching { store.delete(it) } }
        if (keys.isNotEmpty()) audit.record("tombstone reapply", "re-erased ${keys.size} objects after a restore")
        return keys.size
    }
}

/**
 * Retention and orphan sweep (24 privacy, 25). Expired artifacts lose their bytes and are soft-deleted; store
 * objects with no referencing row are removed after a grace window so in-flight writes are not caught.
 */
@Service
class RetentionSweeper(
    private val jdbc: JdbcClient,
    private val store: ArtifactStore,
    private val properties: PrivacyProperties,
    private val async: AsyncProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${secdrill.async.sweep-interval:1s}")
    fun scheduled() {
        if (async.schedulingEnabled) sweepOnce()
    }

    data class SweepResult(val expired: Int, val orphans: Int)

    fun sweepOnce(): SweepResult {
        val now = clock.instant()
        val expired = jdbc.sql(
            "SELECT id, object_key FROM artifacts WHERE deleted_at IS NULL AND expires_at IS NOT NULL AND expires_at <= ?",
        ).param(now.db()).query { rs, _ -> rs.getObject(1, UUID::class.java) to rs.getString(2) }.list()
        expired.forEach { (id, key) ->
            runCatching { store.delete(key) }
            jdbc.sql("UPDATE artifacts SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL").params(now.db(), id).update()
        }
        return SweepResult(expired.size, sweepOrphans(now))
    }

    /** Local store only: remove objects no row references, older than the grace window. S3 enumeration is D-15. */
    private fun sweepOrphans(now: java.time.Instant): Int {
        val local = store as? LocalArtifactStore ?: return 0
        val referenced = (jdbc.sql("SELECT object_key FROM artifacts").query(String::class.java).list() +
            jdbc.sql("SELECT object_key FROM export_jobs WHERE object_key IS NOT NULL").query(String::class.java).list()).filterNotNull().toSet()
        val cutoff = now.minus(properties.orphanGrace)
        var removed = 0
        local.list().forEach { (key, modified) ->
            if (key !in referenced && modified.isBefore(cutoff)) { runCatching { store.delete(key) }; removed++ }
        }
        return removed
    }
}

/** Learner endpoints for exports and deletions; Origin and CSRF are enforced by the identity filters. */
@RestController
class PrivacyController(
    private val exports: ExportService,
    private val deletions: DeletionService,
    private val json: JsonMapper,
) {
    @PostMapping("/v1/exports", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun export(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: ByteArray,
    ): ResponseEntity<AsyncReceipt> {
        // The export body has no idempotency need beyond the default create; the header is optional here.
        val tree = runCatching { json.readTree(body) }.getOrNull() ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON")
        if (!tree.isObject || (tree.propertyNames().toSet() - setOf("sessionId")).isNotEmpty()) throw ApiException(ErrorCode.VALIDATION_FAILED, "Only sessionId is allowed")
        val sessionId = tree["sessionId"]?.takeIf { it.isString }?.asString()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (tree.has("sessionId") && sessionId == null) throw ApiException(ErrorCode.VALIDATION_FAILED, "sessionId must be a UUID")
        return ResponseEntity.status(202).body(exports.request(principal, sessionId))
    }

    @PostMapping("/v1/deletion-requests", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun delete(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: ByteArray,
    ): ResponseEntity<AsyncReceipt> {
        val key = idempotencyKey?.let { runCatching { secdrill.kernel.Uuids.parse(it) }.getOrNull() }
            ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key header must be a UUID")
        val tree = runCatching { json.readTree(body) }.getOrNull() ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON")
        return ResponseEntity.status(202).body(deletions.request(principal, key, DeletionBody.parse(tree)))
    }

    @GetMapping("/v1/async-jobs/{id}")
    fun asyncJob(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID): AsyncReceipt =
        exports.receipt(principal, id) ?: deletions.receipt(principal, id) ?: throw ResourceNotFoundException()

    @GetMapping("/v1/exports/{id}/download")
    fun download(@AuthenticationPrincipal principal: LearnerPrincipal, @PathVariable id: UUID): ResponseEntity<ByteArray> =
        ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(exports.download(principal, id))
}

/** Operator decision on a deletion request (19): SECURITY_ADMIN only, audited by the operator filter and here. */
@RestController
class DeletionOpsController(private val deletions: DeletionService) {
    @PostMapping("/ops/v1/deletion-requests/{id}/decision", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun decide(
        @AuthenticationPrincipal principal: OperatorPrincipal,
        @PathVariable id: UUID,
        @RequestBody body: JsonNode,
    ): ResponseEntity<Void> {
        val approve = body["approve"]?.takeIf { it.isBoolean }?.asBoolean()
            ?: throw ApiException(ErrorCode.VALIDATION_FAILED, "approve must be a boolean")
        deletions.decide(principal, id, approve)
        return ResponseEntity.noContent().build()
    }
}
