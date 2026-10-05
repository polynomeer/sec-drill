package secdrill.simulation

import secdrill.kernel.Digests
import secdrill.kernel.IrActionType
import java.util.SplittableRandom

/**
 * Incident-response model for the tenant-orders scenario (21, 22): a pure reducer `next(state, action, seed)`.
 * Everything it produces is SIMULATED: applying an action here changes the model only, never a real Lab or VM.
 *
 * Model (engine `ir-v1`), all synthetic:
 * - workloads `orders-api` (routes `orders`, `orders-legacy`) and `billing` (route `invoices`)
 * - the attacker holds the integration token `tok-sync`, which the `svc-sync` automation also uses
 * - from its start tick the attacker reads other tenants' orders through `orders`; if that route is disabled it
 *   switches to `orders-legacy`
 * - normal traffic each tick: tenant users on `orders` and `invoices`, and `svc-sync` on `orders` with `tok-sync`
 * - evidence sources AUTH and ACCESS are on, APPLICATION is off until audited; an isolated workload stops its logs
 *
 * Ratios are basis points (0..10000). The model state is versioned with [ENGINE] and replayable from (seed, actions).
 */
object IncidentModel {
    const val ENGINE = "ir-v1"
    val ROUTES = mapOf("orders" to "orders-api", "orders-legacy" to "orders-api", "invoices" to "billing")
    val WORKLOADS = setOf("orders-api", "billing")
    val TOKENS = setOf("tok-sync", "tok-alice", "tok-bob")
    val SOURCES = setOf("AUTH", "ACCESS", "APPLICATION")
    private const val ATTACK_ROUTE = "orders"
    private const val BYPASS_ROUTE = "orders-legacy"
    private const val STOLEN_TOKEN = "tok-sync"

    data class Action(val type: IrActionType, val target: String)

    data class State(
        val tick: Int,
        val attackStartTick: Int,
        val revokedTokens: Set<String>,
        val disabledRoutes: Set<String>,
        val isolatedWorkloads: Set<String>,
        val auditSources: Set<String>,
        val leakedSyntheticRecords: Int,
        val availabilityBps: Int,
        val workloadSuccessBps: Int,
        val evidenceCoverageBps: Int,
        /** Ticks in which the attacker still read data. */
        val exposureTicks: Int,
        val unavailableTicks: Int,
        val failedNormalRequests: Int,
        val evidenceGapTicks: Int,
        /** First tick after which no further record leaked; null while the attack still succeeds (censored). */
        val containedAtTick: Int?,
        /** First tick at or after containment with full normal workload success; null if never (censored). */
        val recoveredAtTick: Int?,
    ) {
        val compromisedIdentities: Set<String> get() = if (tick >= attackStartTick && STOLEN_TOKEN !in revokedTokens) setOf("svc-sync") else emptySet()
        val activeTokens: Set<String> get() = TOKENS - revokedTokens
        val accessibleAssets: Set<String> get() = ROUTES.filter { (route, workload) -> route !in disabledRoutes && workload !in isolatedWorkloads }.keys
        fun digest(): String = Digests.canonical(mapOf(
            "engine" to ENGINE, "tick" to tick, "attackStartTick" to attackStartTick, "revokedTokens" to revokedTokens.sorted(),
            "disabledRoutes" to disabledRoutes.sorted(), "isolatedWorkloads" to isolatedWorkloads.sorted(), "auditSources" to auditSources.sorted(),
            "leaked" to leakedSyntheticRecords, "availability" to availabilityBps, "workloadSuccess" to workloadSuccessBps,
            "evidenceCoverage" to evidenceCoverageBps, "exposureTicks" to exposureTicks, "unavailableTicks" to unavailableTicks,
            "failedNormalRequests" to failedNormalRequests, "evidenceGapTicks" to evidenceGapTicks,
            "containedAtTick" to containedAtTick, "recoveredAtTick" to recoveredAtTick,
        ))
    }

    /** Why an action changed nothing (an action conflict); the state still advances one tick. */
    enum class NoEffect { ALREADY_APPLIED, TARGET_UNAVAILABLE }

    data class Transition(val state: State, val noEffect: NoEffect?, val leakedThisTick: Int, val failedThisTick: Int)

    class InvalidAction(message: String) : IllegalArgumentException(message)

    fun initial(seed: Long, engine: String = ENGINE): State {
        require(engine == ENGINE) { "unsupported engine $engine; replay stored snapshots read-only" }
        val attackStart = 1 + SplittableRandom(seed).nextInt(3)
        return State(0, attackStart, emptySet(), emptySet(), emptySet(), setOf("AUTH", "ACCESS"), 0, 10_000, 10_000, 6_666,
            0, 0, 0, 0, null, null)
    }

    fun validate(action: Action) {
        val known = when (action.type) {
            IrActionType.REVOKE_TOKEN -> TOKENS
            IrActionType.DISABLE_ENDPOINT -> ROUTES.keys
            IrActionType.ISOLATE_WORKLOAD -> WORKLOADS
            IrActionType.ENABLE_AUDIT -> SOURCES
        }
        if (action.target !in known) throw InvalidAction("unknown ${action.type} target")
    }

    /** Applies [action] (if any) at the start of the next tick, then advances the attacker and normal traffic one tick. */
    fun next(state: State, action: Action?, seed: Long): Transition {
        action?.let(::validate)
        var s = state.copy(tick = state.tick + 1)
        var noEffect: NoEffect? = null
        if (action != null) {
            fun <T> Set<T>.plusOrConflict(value: T): Set<T> = if (value in this) { noEffect = NoEffect.ALREADY_APPLIED; this } else this + value
            s = when (action.type) {
                IrActionType.REVOKE_TOKEN -> s.copy(revokedTokens = s.revokedTokens.plusOrConflict(action.target))
                IrActionType.DISABLE_ENDPOINT -> s.copy(disabledRoutes = s.disabledRoutes.plusOrConflict(action.target))
                IrActionType.ISOLATE_WORKLOAD -> s.copy(isolatedWorkloads = s.isolatedWorkloads.plusOrConflict(action.target))
                IrActionType.ENABLE_AUDIT ->
                    // Application logs come from the workloads; with every workload isolated there is nothing left to audit.
                    if (action.target == "APPLICATION" && s.isolatedWorkloads.containsAll(WORKLOADS)) { noEffect = NoEffect.TARGET_UNAVAILABLE; s }
                    else s.copy(auditSources = s.auditSources.plusOrConflict(action.target))
            }
        }
        fun reachable(route: String, token: String) = route !in s.disabledRoutes && ROUTES.getValue(route) !in s.isolatedWorkloads && token !in s.revokedTokens

        // Attacker: deterministic schedule, switches to the legacy route when the main one is disabled.
        val random = SplittableRandom(seed * 1_000_003 + s.tick)
        val route = if (ATTACK_ROUTE in s.disabledRoutes) BYPASS_ROUTE else ATTACK_ROUTE
        val leaked = if (s.tick >= s.attackStartTick && reachable(route, STOLEN_TOKEN)) 3 + random.nextInt(4) else 0

        // Normal traffic: (route, token) requests; any blocked request is a failure of normal work.
        val normal = List(6) { "orders" to (if (it % 2 == 0) "tok-alice" else "tok-bob") } + List(3) { "invoices" to "tok-bob" } + listOf("orders" to STOLEN_TOKEN)
        val failed = normal.count { (r, token) -> !reachable(r, token) }
        val successBps = ((normal.size - failed) * 10_000 + normal.size / 2) / normal.size
        val up = WORKLOADS.count { it !in s.isolatedWorkloads }
        val availabilityBps = up * 10_000 / WORKLOADS.size
        // Evidence: each source counts only while some workload still emits it (AUTH is central and always emits).
        val emitting = s.auditSources.filter { it == "AUTH" || up > 0 }.toSet()
        // Integer arithmetic only, so the digest is identical on every platform.
        val coverage = (emitting.count { it == "AUTH" } * 10_000 * WORKLOADS.size + emitting.count { it != "AUTH" } * 10_000 * up) / (SOURCES.size * WORKLOADS.size)

        val contained = if (leaked == 0 && s.tick >= s.attackStartTick) (s.containedAtTick ?: s.tick) else null
        val recovered = if (contained != null && failed == 0 && availabilityBps == 10_000) (s.recoveredAtTick ?: s.tick) else null
        s = s.copy(
            leakedSyntheticRecords = s.leakedSyntheticRecords + leaked,
            availabilityBps = availabilityBps,
            workloadSuccessBps = successBps,
            evidenceCoverageBps = coverage,
            exposureTicks = s.exposureTicks + if (leaked > 0) 1 else 0,
            unavailableTicks = s.unavailableTicks + if (availabilityBps < 10_000) 1 else 0,
            failedNormalRequests = s.failedNormalRequests + failed,
            // An isolated workload stops its ACCESS/APPLICATION logs: those ticks are missing evidence.
            evidenceGapTicks = s.evidenceGapTicks + if (s.isolatedWorkloads.isNotEmpty()) 1 else 0,
            containedAtTick = contained,
            recoveredAtTick = recovered,
        )
        return Transition(s, noEffect, leaked, failed)
    }

    /** Replays from the initial state; the same (seed, actions) always gives the same state and digest. */
    fun replay(seed: Long, actions: List<Action?>, engine: String = ENGINE): State =
        actions.fold(initial(seed, engine)) { state, action -> next(state, action, seed).state }

    /** Response metrics (21). Times are ticks; null is censored (not reached), never 0. */
    data class Outcome(
        val leakedRecords: Int, val exposureTicks: Int, val timeToContain: Int?, val timeToRecover: Int?,
        val failedNormalRequests: Int, val unavailableTicks: Int, val evidenceGapTicks: Int, val evidenceCoverageBps: Int,
    )

    fun outcome(state: State) = Outcome(
        state.leakedSyntheticRecords, state.exposureTicks, state.containedAtTick?.let { it - state.attackStartTick },
        state.recoveredAtTick?.let { it - state.attackStartTick }, state.failedNormalRequests, state.unavailableTicks,
        state.evidenceGapTicks, state.evidenceCoverageBps,
    )
}
