package secdrill.controlplane.lab

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.evidence.LedgerAppender
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.controlplane.platform.IdempotencyDecision
import secdrill.controlplane.platform.IdempotencyStore
import secdrill.controlplane.platform.OutboxWriter
import secdrill.kernel.ApiException
import secdrill.kernel.ConnectTokens
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.EventType
import secdrill.kernel.EvidenceSource
import secdrill.kernel.LabState
import secdrill.kernel.LabTerminateReason
import secdrill.kernel.Rfc3339
import secdrill.kernel.SessionStatus
import secdrill.kernel.TrustLevel
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Lab settings (00, 17). Times are MVP assumptions; `poolMax` is the 20-Lab pool limit. */
@ConfigurationProperties("secdrill.lab")
data class LabProperties(
    val poolMax: Int = 20,
    val idleTtl: Duration = Duration.ofMinutes(15),
    val hardTtl: Duration = Duration.ofMinutes(60),
    val connectTokenTtl: Duration = Duration.ofSeconds(60),
    /** Base URL of the Lab Gateway, a different origin from the Control Plane (17). */
    val gatewayBaseUrl: String = "http://127.0.0.1:8090",
    /** Ed25519 private key (base64url PKCS#8) for connect tokens. Required under `prod`. */
    val connectSigningKey: String? = null,
    val cleanupRetryDelay: Duration = Duration.ofSeconds(30),
    val cleanupAlertAfter: Duration = Duration.ofMinutes(5),
    /** Isolation profile the runner pool provides (17). */
    val runtimeProfile: String = "local-trusted",
    /** Set only when the strong runtime passed the 17 release checks; nothing sets it today (D-10). */
    val isolationVerified: Boolean = false,
    /**
     * Development only: run content that requires `lab-strong` on a weaker pool. Every such Lab and result is marked
     * demo. Off by default (fail closed) and refused under `prod`.
     */
    val allowUnverifiedIsolation: Boolean = false,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LabProperties::class)
class LabConfig

/** prod needs a configured connect key and an https gateway on its own origin. */
@Component
class LabSafetyCheck(
    private val environment: Environment,
    private val properties: LabProperties,
    private val auth: secdrill.controlplane.identity.AuthProperties,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        properties.connectSigningKey?.let(ConnectTokens::privateKey)
        check(!properties.isolationVerified || properties.runtimeProfile == "lab-strong") {
            "Unsafe lab configuration: only the lab-strong profile can be marked isolation-verified"
        }
        if ("prod" in environment.activeProfiles) {
            check(!properties.allowUnverifiedIsolation) { "Unsafe lab configuration: prod cannot run Labs on unverified isolation" }
            check(properties.connectSigningKey != null && properties.gatewayBaseUrl.startsWith("https://")) {
                "Unsafe lab configuration: prod requires secdrill.lab.connect-signing-key and an https gateway origin"
            }
            // Cookies are scoped by host, not port: the gateway must live on a different host than the platform.
            val gatewayHost = java.net.URI.create(properties.gatewayBaseUrl).host
            check(auth.allowedOrigins.none { java.net.URI.create(it).host == gatewayHost }) {
                "Unsafe lab configuration: the gateway host must differ from every platform origin host"
            }
        }
    }
}

data class LabView(
    val id: UUID, val sessionId: UUID, val generation: Int, val state: LabState, val expiresAt: String,
    /** False for local-trusted or any runtime whose isolation was not verified: results are demo results. */
    val isolationVerified: Boolean,
)

data class SessionView(val id: UUID, val scenarioId: UUID, val scenarioVersionId: UUID, val mode: String, val status: String, val phase: String, val version: Long, val createdAt: String, val lab: LabView?)

data class ConnectView(val connectUrl: String, val expiresAt: String)

/**
 * Learner-facing Lab lifecycle (13, 15, FR-03). One active Lab per user (DB partial unique) and `poolMax` in
 * total; a generation per request; desired state recorded before any runtime exists, so runners and the sweeper
 * converge on it. Provisioning jobs are created claimable at once; the LabRequested event informs other consumers.
 */
@Service
class LabService(
    private val jdbc: JdbcClient,
    private val idempotency: IdempotencyStore,
    private val guard: OwnershipGuard,
    private val ledger: LedgerAppender,
    private val outbox: OutboxWriter,
    private val properties: LabProperties,
    private val flags: secdrill.controlplane.ctf.FlagService,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val ephemeralKey by lazy { ConnectTokens.generate() }
    private fun signingKey() = ConnectTokens.privateKey(properties.connectSigningKey ?: ephemeralKey.first)

    /** Development fallback key's public half, for wiring a local gateway when no key is configured. */
    fun developmentGatewayKey(): String? = if (properties.connectSigningKey == null) ephemeralKey.second else null

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
    private fun Instant.db(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    @Transactional
    fun requestLab(principal: LearnerPrincipal, sessionId: UUID, key: UUID, expectedVersion: Long, requestDigest: String): Pair<Int, String> {
        val owner = principal.userId.value
        val route = "POST /v1/sessions/$sessionId/labs"
        when (val decision = idempotency.begin(owner, route, key, requestDigest)) {
            is IdempotencyDecision.Replay -> return decision.status to decision.body
            IdempotencyDecision.Conflict -> throw ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency-Key was used with a different request")
            IdempotencyDecision.Proceed -> Unit
        }
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        // Operations drain (25): pause new Labs while keeping existing ones; the learner may retry later.
        if (jdbc.sql("SELECT draining FROM lab_pool WHERE id = 1").query(Boolean::class.java).single()) {
            throw ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The Lab pool is paused for maintenance; try again shortly")
        }
        val session = jdbc.sql("SELECT status, version, scenario_version_id FROM sessions WHERE id = ? FOR UPDATE").param(sessionId)
            .query { rs, _ -> Triple(SessionStatus.valueOf(rs.getString(1)), rs.getLong(2), rs.getObject(3, UUID::class.java)) }.single()
        if (session.first !in setOf(SessionStatus.CREATED, SessionStatus.ACTIVE)) throw ApiException(ErrorCode.INVALID_STATE, "Session does not accept a Lab")
        if (session.second != expectedVersion) throw ApiException(ErrorCode.VERSION_CONFLICT, "Session version changed", ErrorDetails(latestVersion = session.second))
        if (jdbc.sql("SELECT count(*) FROM labs WHERE session_id = ? AND cleanup_confirmed_at IS NULL").param(sessionId).query(Int::class.java).single() > 0) {
            throw ApiException(ErrorCode.INVALID_STATE, "This Session already has a Lab")
        }
        // Pool quota: one advisory lock serializes the count with the insert.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('lab-pool', 0))").query().singleRow()
        if (jdbc.sql("SELECT count(*) FROM labs WHERE cleanup_confirmed_at IS NULL").query(Int::class.java).single() >= properties.poolMax) {
            throw ApiException(ErrorCode.QUOTA_EXCEEDED, "All Lab slots are in use; try again shortly")
        }

        val now = now()
        val manifest = json.readTree(jdbc.sql("SELECT public_manifest::text FROM scenario_versions WHERE id = ?").param(session.third).query(String::class.java).single())
        // Fail closed (17): content requiring a strong runtime does not run on a weaker pool unless development
        // explicitly allows it, and then every result is a demo result. A manifest without a profile counts as strong.
        val required = manifest["runtime"]?.get("profile")?.takeIf { it.isString }?.asString() ?: "lab-strong"
        if (required != properties.runtimeProfile && !properties.allowUnverifiedIsolation) {
            throw ApiException(ErrorCode.SERVICE_UNAVAILABLE, "No runtime with the required isolation is available")
        }
        val verified = properties.isolationVerified && properties.runtimeProfile == "lab-strong"
        val hardTtl = manifest["runtime"]?.get("hardTtlSeconds")?.asLong()?.let(Duration::ofSeconds)?.coerceAtMost(properties.hardTtl) ?: properties.hardTtl
        val idleTtl = manifest["runtime"]?.get("idleTtlSeconds")?.asLong()?.let(Duration::ofSeconds)?.coerceAtMost(properties.idleTtl) ?: properties.idleTtl
        val labId = UUID.randomUUID()
        val generation = jdbc.sql("SELECT coalesce(max(generation), 0) + 1 FROM labs WHERE session_id = ?").param(sessionId).query(Int::class.java).single()
        try {
            jdbc.sql(
                """INSERT INTO labs(id, session_id, owner_id, generation, state, desired_state, expires_at, idle_expires_at, created_at,
                   flag_nonce, flag_key_version, runtime_profile, isolation_verified) VALUES (?, ?, ?, ?, 'REQUESTED', 'RUNNING', ?, ?, ?, ?, ?, ?, ?)""",
            ).params(
                labId, sessionId, owner, generation, now.plus(hardTtl).db(), now.plus(idleTtl).db(), now.db(),
                flags.newNonce(), flags.activeKeyVersion, properties.runtimeProfile, verified,
            ).update()
        } catch (error: DuplicateKeyException) {
            throw ApiException(ErrorCode.QUOTA_EXCEEDED, "You already have an active Lab")
        }
        jdbc.sql("INSERT INTO jobs(id, lab_id, session_id, kind, state, dispatched_at, due_at, created_at) VALUES (?, ?, ?, 'PROVISION', 'DISPATCHED', ?, ?, ?)")
            .params(UUID.randomUUID(), labId, sessionId, now.db(), now.db(), now.db()).update()
        jdbc.sql("UPDATE sessions SET version = version + 1 WHERE id = ?").param(sessionId).update()
        val templateDigest = Digests.canonical(mapOf("runtime" to (manifest["runtime"]?.toString() ?: "{}")))
        val evidence = ledger.append(sessionId, EventType.LabRequested.name, EvidenceSource.CONTROL, TrustLevel.SERVER_VERIFIED,
            mapOf("labId" to labId, "generation" to generation))
        outbox.append(EventType.LabRequested, labId, 0, sessionId, labId,
            mapOf("labId" to labId.toString(), "generation" to generation, "templateDigest" to templateDigest), seq = evidence.seq)
        val body = json.writeValueAsString(LabView(labId, sessionId, generation, LabState.REQUESTED, Rfc3339.format(now.plus(hardTtl)), verified))
        idempotency.record(owner, route, key, requestDigest, 202, body)
        return 202 to body
    }

    /** Cancels the Session (13: user cancel before COMPLETED) and terminates its Lab. Idempotent for CANCELLED. */
    @Transactional
    fun stopSession(principal: LearnerPrincipal, sessionId: UUID, expectedVersion: Long): SessionView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val (status, version) = jdbc.sql("SELECT status, version FROM sessions WHERE id = ? FOR UPDATE").param(sessionId)
            .query { rs, _ -> SessionStatus.valueOf(rs.getString(1)) to rs.getLong(2) }.single()
        if (status.terminal) throw ApiException(ErrorCode.INVALID_STATE, "Session already ended")
        if (version != expectedVersion) throw ApiException(ErrorCode.VERSION_CONFLICT, "Session version changed", ErrorDetails(latestVersion = version))
        jdbc.sql("UPDATE sessions SET status = 'CANCELLED', version = version + 1 WHERE id = ?").param(sessionId).update()
        jdbc.sql("SELECT id FROM labs WHERE session_id = ? AND cleanup_confirmed_at IS NULL").param(sessionId).query(UUID::class.java).list().filterNotNull()
            .forEach { requestTermination(it, LabTerminateReason.USER_STOP) }
        return sessionView(sessionId)
    }

    /**
     * Sets desired state TERMINATED (17 order: the gateway stops admitting at once because it checks state per request).
     * - not yet leased: no runtime can exist; cancel the job and close the Lab with a "never created" receipt
     * - provisioning: TERMINATING; the late provisioned callback turns into cleanup
     * - running: TERMINATING plus a CLEANUP job for the runner that owns it
     */
    @Transactional
    fun requestTermination(labId: UUID, reason: LabTerminateReason) {
        val lab = jdbc.sql("SELECT state, desired_state, session_id, generation, runtime_ref FROM labs WHERE id = ? FOR UPDATE").param(labId)
            .query { rs, _ -> LabRow(LabState.valueOf(rs.getString(1)), rs.getString(2), rs.getObject(3, UUID::class.java), rs.getInt(4), rs.getString(5)) }
            .optional().orElse(null) ?: return
        if (lab.desired == "TERMINATED" || lab.state == LabState.TERMINATED) return
        val now = now()
        // One statement: a READY row may never carry desired TERMINATED (labs_ready_wanted).
        jdbc.sql(
            """UPDATE labs SET desired_state = 'TERMINATED', terminate_reason = ?, terminate_requested_at = ?, version = version + 1,
               state = CASE WHEN state IN ('READY', 'PROVISIONING') THEN 'TERMINATING' ELSE state END WHERE id = ?""",
        ).params(reason.name, now.db(), labId).update()
        val provision = jdbc.sql("SELECT id, state, attempt FROM jobs WHERE lab_id = ? AND kind = 'PROVISION' ORDER BY revision DESC LIMIT 1 FOR UPDATE")
            .param(labId).query { rs, _ -> Triple(rs.getObject(1, UUID::class.java), rs.getString(2), rs.getInt(3)) }.optional().orElse(null)
        val neverStarted = lab.state == LabState.REQUESTED && provision != null && provision.second in setOf("PENDING", "DISPATCHED") && provision.third == 0
        if (neverStarted) {
            jdbc.sql("UPDATE jobs SET state = 'CANCELLED', version = version + 1 WHERE id = ?").param(provision.first).update()
            closeWithoutRuntime(labId, lab.sessionId, lab.generation, "never-created")
        } else {
            jdbc.sql("UPDATE labs SET state = 'TERMINATING' WHERE id = ? AND state = 'REQUESTED'").param(labId).update()
            if (lab.state == LabState.READY) scheduleCleanup(labId, lab.sessionId)
        }
        outbox.append(EventType.LabTerminationRequested, labId, 1, lab.sessionId, labId, mapOf("labId" to labId.toString(), "generation" to lab.generation))
    }

    /** Creates the next CLEANUP job revision for a Lab unless one is already live. */
    fun scheduleCleanup(labId: UUID, sessionId: UUID) {
        val live = jdbc.sql("SELECT count(*) FROM jobs WHERE lab_id = ? AND kind = 'CLEANUP' AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')")
            .param(labId).query(Int::class.java).single()
        if (live > 0) return
        val now = now()
        val revision = jdbc.sql("SELECT coalesce(max(revision), 0) + 1 FROM jobs WHERE lab_id = ? AND kind = 'CLEANUP'").param(labId).query(Int::class.java).single()
        jdbc.sql("INSERT INTO jobs(id, lab_id, session_id, kind, revision, state, dispatched_at, due_at, created_at) VALUES (?, ?, ?, 'CLEANUP', ?, 'DISPATCHED', ?, ?, ?)")
            .params(UUID.randomUUID(), labId, sessionId, revision, now.db(), now.db(), now.db()).update()
    }

    /** No runtime exists for this Lab: record that as its cleanup receipt and free the quota (F-01). */
    fun closeWithoutRuntime(labId: UUID, sessionId: UUID, generation: Int, why: String) {
        val now = now()
        val receipt = json.writeValueAsString(mapOf("runtimeRef" to null, "removed" to emptyList<String>(), "completedAt" to Rfc3339.format(now), "note" to why))
        jdbc.sql("UPDATE labs SET state = 'TERMINATED', cleanup_confirmed_at = ?, cleanup_receipt = ?::jsonb, version = version + 1 WHERE id = ?")
            .params(now.db(), receipt, labId).update()
        outbox.append(EventType.LabTerminated, labId, 2, sessionId, labId, mapOf(
            "labId" to labId.toString(), "generation" to generation,
            "cleanupReceipt" to mapOf("key" to "labs/$labId/receipt", "digest" to Digests.sha256Hex(receipt.toByteArray()), "byteSize" to receipt.length),
        ))
    }

    /** Signs a short connect token for the separate-origin gateway. Only for the owner's READY Lab. */
    @Transactional
    fun connect(principal: LearnerPrincipal, sessionId: UUID, labId: UUID): ConnectView {
        guard.requireOwned(principal, OwnedResource.SESSION, sessionId)
        val generation = jdbc.sql("SELECT generation FROM labs WHERE id = ? AND session_id = ? AND state = 'READY' AND desired_state = 'RUNNING'")
            .params(labId, sessionId).query(Int::class.java).optional()
            .orElseThrow { ApiException(ErrorCode.NOT_READY, "Lab is not ready") }
        val expires = clock.instant().plus(properties.connectTokenTtl)
        val token = ConnectTokens.sign(ConnectTokens.Claims(labId, generation, principal.userId.value, expires, secdrill.controlplane.identity.Secrets.newToken()), signingKey())
        return ConnectView("${properties.gatewayBaseUrl.trimEnd('/')}/connect?token=$token", Rfc3339.format(expires))
    }

    /** Operations drain switch (25): true pauses new Lab requests. Existing Labs keep running. */
    @Transactional
    fun setDraining(draining: Boolean) {
        jdbc.sql("UPDATE lab_pool SET draining = ?, updated_at = ? WHERE id = 1").params(draining, now().db()).update()
    }

    fun sessionView(sessionId: UUID): SessionView {
        val lab = jdbc.sql("SELECT id, generation, state, expires_at, isolation_verified FROM labs WHERE session_id = ? ORDER BY generation DESC LIMIT 1").param(sessionId)
            .query { rs, _ ->
                LabView(rs.getObject(1, UUID::class.java), sessionId, rs.getInt(2), LabState.valueOf(rs.getString(3)),
                    Rfc3339.format(rs.getObject(4, OffsetDateTime::class.java).toInstant()), rs.getBoolean(5))
            }
            .optional().orElse(null)
        return jdbc.sql(
            "SELECT s.scenario_version_id, s.mode, s.status, s.phase, s.version, s.created_at, sv.scenario_id FROM sessions s JOIN scenario_versions sv ON sv.id = s.scenario_version_id WHERE s.id = ?",
        ).param(sessionId).query { rs, _ ->
            SessionView(sessionId, rs.getObject(7, UUID::class.java), rs.getObject(1, UUID::class.java), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                Rfc3339.format(rs.getObject(6, OffsetDateTime::class.java).toInstant().truncatedTo(ChronoUnit.SECONDS)), lab)
        }.single()
    }

    private data class LabRow(val state: LabState, val desired: String, val sessionId: UUID, val generation: Int, val runtimeRef: String?)
}
