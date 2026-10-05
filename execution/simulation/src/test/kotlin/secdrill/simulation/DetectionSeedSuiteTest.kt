package secdrill.simulation

import tools.jackson.databind.json.JsonMapper
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seed suite for the tenant-leak detection drill (23, T13): 20 boundary seeds and 100 random seeds. For every seed
 * the behavioural rule must PASS the hidden holdout (the generator stays solvable) and the memorization rule must
 * FAIL it (name memorization never transfers). This is the content-side seed validation, run on the pure engine.
 */
class DetectionSeedSuiteTest {
    private val json = JsonMapper.builder().build()
    private val thresholds = DetectionThresholds(minRecallBps = 9000, minPrecisionBps = 8000, maxP95LatencySeconds = 30)
    private fun rule(text: String) = RuleParser.parse(json.readTree(text))
    private val behaviour = rule("""{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"and","children":[
        {"op":"neq","field":"tenantId","compareField":"resourceTenantId"},{"op":"eq","field":"status","value":200}]}}""")

    private fun holdoutGate(seed: Long, r: Rule): Gate {
        val data = SyntheticLogs.generate(seed, SyntheticLogs.Variant.HOLDOUT)
        return DetectionGate.of(DetectionScorer.score(RuleEvaluator().alerts(r, data.events), data.truth), thresholds)
    }

    private fun trainingAttacker(seed: Long): String {
        val training = SyntheticLogs.generate(seed, SyntheticLogs.Variant.TRAINING)
        return training.events.filter { it.tenantId != it.resourceTenantId }.groupBy { it.actorId }.maxBy { it.value.size }.key!!
    }

    @Test
    fun `boundary and random seeds stay solvable by behaviour and resistant to memorization`() {
        val boundary = (0L until 20L).toList()
        val random = SplittableRandom(20260513L).let { r -> List(100) { r.nextLong(1, 1_000_000_000L) } }
        var checked = 0
        (boundary + random).forEach { seed ->
            assertEquals(Gate.PASS, holdoutGate(seed, behaviour), "seed $seed: behavioural rule must detect the attack")
            val memorized = rule("""{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"eq","field":"actorId","value":"${trainingAttacker(seed)}"}}""")
            assertEquals(Gate.FAIL, holdoutGate(seed, memorized), "seed $seed: memorizing the training name must not transfer")
            checked++
        }
        assertEquals(120, checked)
    }

    @Test
    fun `training and holdout never share actor names across the suite`() {
        (0L until 20L).forEach { seed ->
            val training = SyntheticLogs.generate(seed, SyntheticLogs.Variant.TRAINING).events.mapNotNull { it.actorId }.toSet()
            val holdout = SyntheticLogs.generate(seed, SyntheticLogs.Variant.HOLDOUT).events.mapNotNull { it.actorId }.toSet()
            assertTrue((training intersect holdout).isEmpty(), "seed $seed: holdout reuses a training name")
        }
    }
}
