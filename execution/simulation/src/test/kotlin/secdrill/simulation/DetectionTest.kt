package secdrill.simulation

import tools.jackson.databind.json.JsonMapper
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetectionTest {
    private val json = JsonMapper.builder().build()
    private fun rule(text: String) = RuleParser.parse(json.readTree(text))
    private val thresholds = DetectionThresholds(minRecallBps = 9000, minPrecisionBps = 8000, maxP95LatencySeconds = 30)
    private val training = SyntheticLogs.generate(7, SyntheticLogs.Variant.TRAINING)
    private val holdout = SyntheticLogs.generate(7, SyntheticLogs.Variant.HOLDOUT)

    private fun metrics(rule: Rule, data: Dataset) = DetectionScorer.score(RuleEvaluator().alerts(rule, data.events), data.truth)

    /** Burst of successful cross-tenant reads by one actor: behaviour, not names. */
    private val behaviour = """{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"and","children":[
        {"op":"neq","field":"tenantId","compareField":"resourceTenantId"},{"op":"eq","field":"status","value":200}]}}"""

    @Test
    fun `a behavioural rule passes training and the renamed holdout`() {
        listOf(training, holdout).forEach { data ->
            val m = metrics(rule(behaviour), data)
            assertEquals(3, m.truePositives, m.toString())
            assertEquals(0, m.falseNegatives)
            assertEquals(Gate.PASS, DetectionGate.of(m, thresholds), m.toString())
            assertTrue(m.latencies.all { it <= 30 })
        }
    }

    @Test
    fun `memorizing the training attacker's name or route passes training but fails the holdout`() {
        val attacker = training.events.groupBy { it.actorId }.maxBy { (_, events) -> events.count { it.tenantId != it.resourceTenantId } }.key!!
        val byName = rule("""{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"eq","field":"actorId","value":"$attacker"}}""")
        val byRoute = rule("""{"op":"count_gte","count":5,"windowSeconds":60,"predicate":{"op":"and","children":[
            {"op":"neq","field":"tenantId","compareField":"resourceTenantId"},{"op":"eq","field":"routeGroup","value":"orders"}]}}""")
        listOf(byName, byRoute).forEach {
            assertEquals(Gate.PASS, DetectionGate.of(metrics(it, training), thresholds), "looks good on training")
            val m = metrics(it, holdout)
            assertEquals(Gate.FAIL, DetectionGate.of(m, thresholds), "holdout uses other names: $m")
            assertEquals(0, m.truePositives)
        }
    }

    @Test
    fun `a single cross-tenant read alert drowns in false positives from legitimate support work`() {
        val naive = rule("""{"op":"neq","field":"tenantId","compareField":"resourceTenantId"}""")
        val m = metrics(naive, holdout)
        assertEquals(3, m.truePositives, "one TP per episode, however many alerts")
        assertTrue(m.falsePositives > 3, m.toString())
        assertEquals(Gate.FAIL, DetectionGate.of(m, thresholds))
    }

    @Test
    fun `zero denominators are N A, never 0 or 100 percent`() {
        val nothing = rule("""{"op":"eq","field":"eventType","value":"never.happens"}""")
        val missed = metrics(nothing, holdout)
        assertTrue(missed.precision.isNa && missed.f1.isNa, "no alerts: precision and F1 are N/A")
        assertEquals(0, missed.recall.bps)
        assertNull(missed.p95LatencySeconds, "no detection, no latency")
        assertEquals(3, missed.falseNegatives, "missed episodes stay FN")
        assertEquals(Gate.FAIL, DetectionGate.of(missed, thresholds))

        val quiet = Dataset(holdout.events, GroundTruth(emptyList(), holdout.truth.normalWindows))
        val none = metrics(rule(behaviour), quiet)
        assertTrue(none.recall.isNa, "no episodes: recall is N/A")
        assertEquals(Gate.INCONCLUSIVE, DetectionGate.of(none, thresholds))
        assertTrue(DetectionScorer.score(emptyList(), GroundTruth(emptyList(), emptyList())).falsePositiveRate.isNa)
    }

    @Test
    fun `rules never see ground truth labels`() {
        val fields = LogEvent::class.memberProperties.map { it.name }.toSet()
        assertEquals(setOf("eventId", "time", "eventType", "actorId", "tenantId", "resourceTenantId", "status", "routeGroup"), fields)
        assertTrue(Field.entries.none { it.wire.contains("label", ignoreCase = true) || it.wire.contains("episode", ignoreCase = true) })
        assertFailsWith<RuleInvalid> { rule("""{"op":"eq","field":"attackLabel","value":"attack"}""") }
    }

    @Test
    fun `limits on depth, nodes, window, nesting and evaluation time are enforced`() {
        var deep = """{"op":"eq","field":"status","value":200}"""
        repeat(8) { deep = """{"op":"and","children":[$deep,{"op":"eq","field":"status","value":200}]}""" }
        assertFailsWith<RuleInvalid> { rule(deep) }.also { assertTrue(it.problems.any { p -> "deeper" in p }) }
        val leaf = """{"op":"eq","field":"status","value":200}"""
        val eight = """{"op":"or","children":[${List(8) { leaf }.joinToString(",")}]}"""
        val wide = """{"op":"and","children":[${List(8) { eight }.joinToString(",")}]}"""
        assertTrue(assertFailsWith<RuleInvalid> { rule(wide) }.problems.any { "64 nodes" in it })
        assertFailsWith<RuleInvalid> { rule("""{"op":"count_gte","count":2,"windowSeconds":301,"predicate":$leaf}""") }
        assertFailsWith<RuleInvalid> { rule("""{"op":"count_gte","count":2,"windowSeconds":60,"predicate":{"op":"count_gte","count":2,"windowSeconds":60,"predicate":$leaf}}""") }
        assertFailsWith<RuleInvalid> { rule("""{"op":"regex","field":"actorId","value":".*"}""") }
        assertFailsWith<RuleInvalid> { rule("""{"op":"eq","field":"status","value":200,"compareField":"tenantId"}""") }

        var now = 0L
        val slow = RuleEvaluator(budgetNanos = 1_000) { now += 1_000; now }
        assertFailsWith<RuleBudgetExceeded> { slow.alerts(rule(behaviour), holdout.events) }
    }

    @Test
    fun `missing fields compare false and duplicate events count once`() {
        val event = LogEvent("e1", 10, "resource.read", "u-1", "t-1", null, 200, "orders")
        assertTrue(RuleEvaluator().alerts(rule("""{"op":"neq","field":"tenantId","compareField":"resourceTenantId"}"""), listOf(event)).isEmpty())
        assertTrue(RuleEvaluator().alerts(rule("""{"op":"eq","field":"resourceTenantId","value":"t-1"}"""), listOf(event)).isEmpty())
        val twice = rule("""{"op":"count_gte","count":2,"windowSeconds":60,"predicate":{"op":"eq","field":"status","value":200}}""")
        assertTrue(RuleEvaluator().alerts(twice, listOf(event, event)).isEmpty(), "the same eventId is counted once")
        assertEquals(1, RuleEvaluator().alerts(twice, listOf(event, event.copy(eventId = "e2", time = 20))).size)
    }

    @Test
    fun `the same seed and variant always give the same dataset`() {
        assertEquals(SyntheticLogs.generate(42, SyntheticLogs.Variant.HOLDOUT), SyntheticLogs.generate(42, SyntheticLogs.Variant.HOLDOUT))
        val trainingActors = training.events.mapNotNull { it.actorId }.toSet()
        assertTrue(holdout.events.mapNotNull { it.actorId }.none { it in trainingActors }, "holdout actors are renamed")
    }
}
