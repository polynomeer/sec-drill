package secdrill.simulation

/**
 * IR replay checkpoints (22). One tick is 10 simulated seconds, so a checkpoint every [INTERVAL_TICKS] ticks is the
 * "30 simulated seconds" rule. A checkpoint stores the full reducer state and its digest; seeking applies the
 * actions after the nearest earlier checkpoint. Seeking and replaying from the start must give the same digest.
 */
object Checkpoints {
    const val INTERVAL_TICKS = 3
    const val SIMULATED_SECONDS_PER_TICK = 10

    fun isCheckpoint(tick: Int) = tick > 0 && tick % INTERVAL_TICKS == 0

    fun toMap(state: IncidentModel.State): Map<String, Any?> = mapOf(
        "engine" to IncidentModel.ENGINE, "tick" to state.tick, "attackStartTick" to state.attackStartTick,
        "revokedTokens" to state.revokedTokens.sorted(), "disabledRoutes" to state.disabledRoutes.sorted(),
        "isolatedWorkloads" to state.isolatedWorkloads.sorted(), "auditSources" to state.auditSources.sorted(),
        "leakedSyntheticRecords" to state.leakedSyntheticRecords, "availabilityBps" to state.availabilityBps,
        "workloadSuccessBps" to state.workloadSuccessBps, "evidenceCoverageBps" to state.evidenceCoverageBps,
        "exposureTicks" to state.exposureTicks, "unavailableTicks" to state.unavailableTicks,
        "failedNormalRequests" to state.failedNormalRequests, "evidenceGapTicks" to state.evidenceGapTicks,
        "containedAtTick" to state.containedAtTick, "recoveredAtTick" to state.recoveredAtTick,
    )

    @Suppress("UNCHECKED_CAST")
    fun fromMap(map: Map<String, Any?>): IncidentModel.State {
        require(map["engine"] == IncidentModel.ENGINE) { "checkpoint from another engine" }
        fun int(key: String) = (map[key] as Number).toInt()
        fun intOrNull(key: String) = (map[key] as Number?)?.toInt()
        fun set(key: String) = (map[key] as List<String>).toSet()
        return IncidentModel.State(
            int("tick"), int("attackStartTick"), set("revokedTokens"), set("disabledRoutes"), set("isolatedWorkloads"), set("auditSources"),
            int("leakedSyntheticRecords"), int("availabilityBps"), int("workloadSuccessBps"), int("evidenceCoverageBps"),
            int("exposureTicks"), int("unavailableTicks"), int("failedNormalRequests"), int("evidenceGapTicks"),
            intOrNull("containedAtTick"), intOrNull("recoveredAtTick"),
        )
    }

    /**
     * State at [tick]: from the latest checkpoint at or before it, then the remaining recorded actions (actions[i] is
     * the action applied at tick i + 1). A corrupted checkpoint (digest mismatch) is ignored and replay starts over.
     */
    fun seek(seed: Long, actions: List<IncidentModel.Action>, tick: Int, checkpoints: Map<Int, Pair<Map<String, Any?>, String>>): Pair<IncidentModel.State, Int> {
        require(tick in 0..actions.size) { "tick outside the recorded range" }
        val usable = checkpoints.filterKeys { it <= tick }.toSortedMap().entries.reversed()
            .firstOrNull { (_, value) -> runCatching { fromMap(value.first).digest() == value.second }.getOrDefault(false) }
        var state = usable?.let { fromMap(it.value.first) } ?: IncidentModel.initial(seed)
        val from = usable?.key ?: 0
        for (index in from until tick) state = IncidentModel.next(state, actions[index], seed).state
        return state to from
    }
}
