package secdrill.simulation

import secdrill.kernel.IrActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckpointsTest {
    private val seed = 99L
    private val actions = listOf(
        IncidentModel.Action(IrActionType.ENABLE_AUDIT, "APPLICATION"), IncidentModel.Action(IrActionType.DISABLE_ENDPOINT, "orders"),
        IncidentModel.Action(IrActionType.DISABLE_ENDPOINT, "orders-legacy"), IncidentModel.Action(IrActionType.REVOKE_TOKEN, "tok-sync"),
        IncidentModel.Action(IrActionType.ISOLATE_WORKLOAD, "billing"), IncidentModel.Action(IrActionType.ENABLE_AUDIT, "AUTH"),
        IncidentModel.Action(IrActionType.ISOLATE_WORKLOAD, "orders-api"),
    )

    private fun checkpoints(): Map<Int, Pair<Map<String, Any?>, String>> {
        var state = IncidentModel.initial(seed)
        val stored = mutableMapOf<Int, Pair<Map<String, Any?>, String>>()
        actions.forEach { action ->
            state = IncidentModel.next(state, action, seed).state
            if (Checkpoints.isCheckpoint(state.tick)) stored[state.tick] = Checkpoints.toMap(state) to state.digest()
        }
        return stored
    }

    @Test
    fun `seeking from a checkpoint gives the same digest as replaying from the start at every tick`() {
        val stored = checkpoints()
        assertEquals(setOf(3, 6), stored.keys)
        for (tick in 0..actions.size) {
            val fromStart = IncidentModel.replay(seed, actions.take(tick))
            val (sought, from) = Checkpoints.seek(seed, actions, tick, stored)
            assertEquals(fromStart.digest(), sought.digest(), "tick $tick")
            assertEquals(stored.keys.filter { it <= tick }.maxOrNull() ?: 0, from)
        }
    }

    @Test
    fun `a corrupted checkpoint is ignored, not trusted`() {
        val stored = checkpoints().toMutableMap()
        val (state, digest) = stored.getValue(6)
        stored[6] = (state + ("leakedSyntheticRecords" to 0)) to digest
        val (sought, from) = Checkpoints.seek(seed, actions, 7, stored)
        assertEquals(3, from, "falls back to the previous valid checkpoint")
        assertEquals(IncidentModel.replay(seed, actions).digest(), sought.digest())
        assertFailsWith<IllegalArgumentException> { Checkpoints.seek(seed, actions, 8, stored) }
        assertFailsWith<IllegalArgumentException> { Checkpoints.fromMap(state + ("engine" to "ir-v0")) }
    }
}
