package secdrill.simulation

import secdrill.simulation.IncidentModel.Action
import secdrill.kernel.IrActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncidentModelTest {
    private val seed = 11L
    private fun run(vararg actions: Action?, ticks: Int = 12) = IncidentModel.replay(seed, actions.toList() + List(ticks - actions.size) { null })

    @Test
    fun `the same seed and actions reproduce the same state and digest`() {
        val plan = arrayOf(null, null, Action(IrActionType.ENABLE_AUDIT, "APPLICATION"), Action(IrActionType.REVOKE_TOKEN, "tok-sync"))
        assertEquals(run(*plan).digest(), run(*plan).digest())
        val seeds = (1L..5L).map { IncidentModel.replay(it, List(12) { null }).digest() }.toSet()
        assertTrue(seeds.size > 1, "the seed drives the attacker schedule")
        assertFailsWith<IllegalArgumentException> { IncidentModel.initial(seed, "ir-v0") }
    }

    @Test
    fun `doing nothing leaks records and is never contained or recovered (censored, not 0)`() {
        val outcome = IncidentModel.outcome(run())
        assertTrue(outcome.leakedRecords > 0)
        assertNull(outcome.timeToContain, "not contained")
        assertNull(outcome.timeToRecover, "not recovered")
        assertEquals(0, outcome.failedNormalRequests)
    }

    @Test
    fun `revoking the stolen token contains the attack but breaks the automation that shared it`() {
        val state = run(null, Action(IrActionType.REVOKE_TOKEN, "tok-sync"))
        val outcome = IncidentModel.outcome(state)
        assertNotNull(outcome.timeToContain)
        assertTrue(outcome.failedNormalRequests > 0, "svc-sync used the same token")
        assertNull(outcome.timeToRecover, "the automation does not recover by itself")
        assertTrue(state.workloadSuccessBps < 10_000)
        assertEquals(10_000, state.availabilityBps)
    }

    @Test
    fun `disabling only the main route is bypassed through the legacy route`() {
        val one = run(null, Action(IrActionType.DISABLE_ENDPOINT, "orders"))
        assertNull(IncidentModel.outcome(one).timeToContain, "the attacker switches to orders-legacy")
        val both = run(null, Action(IrActionType.DISABLE_ENDPOINT, "orders"), Action(IrActionType.DISABLE_ENDPOINT, "orders-legacy"))
        assertNotNull(IncidentModel.outcome(both).timeToContain)
        assertTrue(both.workloadSuccessBps < 10_000, "normal order traffic now fails")
    }

    @Test
    fun `isolating the workload stops the attack at the cost of availability and logs`() {
        val state = run(null, Action(IrActionType.ISOLATE_WORKLOAD, "orders-api"))
        val outcome = IncidentModel.outcome(state)
        assertNotNull(outcome.timeToContain)
        assertTrue(state.availabilityBps < 10_000 && outcome.unavailableTicks > 0)
        assertTrue(outcome.evidenceGapTicks > 0, "an isolated workload stops its logs")
        assertTrue(state.evidenceCoverageBps < IncidentModel.initial(seed).evidenceCoverageBps)
    }

    @Test
    fun `enabling audit raises coverage from then on and creates no past evidence`() {
        val before = IncidentModel.replay(seed, List(3) { null })
        val after = IncidentModel.next(before, Action(IrActionType.ENABLE_AUDIT, "APPLICATION"), seed).state
        assertTrue(after.evidenceCoverageBps > before.evidenceCoverageBps)
        assertEquals(before.leakedSyntheticRecords <= after.leakedSyntheticRecords, true)
        assertEquals(before.evidenceGapTicks, after.evidenceGapTicks, "no evidence is invented for past ticks")
        assertNull(IncidentModel.outcome(after).timeToContain, "audit alone does not contain anything")
    }

    @Test
    fun `conflicting or repeated actions change nothing and say why`() {
        val revoked = IncidentModel.replay(seed, listOf(null, Action(IrActionType.REVOKE_TOKEN, "tok-sync")))
        val again = IncidentModel.next(revoked, Action(IrActionType.REVOKE_TOKEN, "tok-sync"), seed)
        assertEquals(IncidentModel.NoEffect.ALREADY_APPLIED, again.noEffect)
        assertEquals(revoked.revokedTokens, again.state.revokedTokens)
        val dark = IncidentModel.replay(seed, listOf(Action(IrActionType.ISOLATE_WORKLOAD, "orders-api"), Action(IrActionType.ISOLATE_WORKLOAD, "billing")))
        assertEquals(IncidentModel.NoEffect.TARGET_UNAVAILABLE, IncidentModel.next(dark, Action(IrActionType.ENABLE_AUDIT, "APPLICATION"), seed).noEffect)
        assertFailsWith<IncidentModel.InvalidAction> { IncidentModel.next(revoked, Action(IrActionType.REVOKE_TOKEN, "tok-unknown"), seed) }
    }

    @Test
    fun `blocking everything stops the attack but shows the damage to normal work`() {
        val all = run(null, Action(IrActionType.ISOLATE_WORKLOAD, "orders-api"), Action(IrActionType.ISOLATE_WORKLOAD, "billing"))
        val outcome = IncidentModel.outcome(all)
        assertNotNull(outcome.timeToContain)
        assertEquals(0, all.availabilityBps)
        assertEquals(0, all.workloadSuccessBps)
        assertNull(outcome.timeToRecover)
    }
}
