package secdrill.learning

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillPolicyTest {
    private var counter = 0
    private fun outcome(
        kind: String = "PATCH", mode: String = "PURPLE", verdict: String = "PASS", family: String = "f1", day: Int = 1,
        exposure: Exposure = Exposure.INDEPENDENT, demo: Boolean = false, transfer: Boolean = false, root: String? = null,
    ) = GradedOutcome("ev-${counter++}", listOf("evidence-$counter"), kind, mode, verdict, demo, family, LocalDate.of(2026, 10, day), exposure, transfer, root ?: "root-$counter")

    private fun skill(outcomes: List<GradedOutcome>, key: String = "SECURE_PATCHING") = SkillPolicy.project(outcomes).single { it.key == key }

    @Test
    fun `platform errors, demo results, wrong flags and postmortems are never samples`() {
        val ignored = listOf(
            outcome(verdict = "SYSTEM_ERROR"), outcome(demo = true), outcome(kind = "FLAG", mode = "CTF", verdict = "FAIL"), outcome(kind = "POSTMORTEM"),
        )
        assertTrue(SkillPolicy.samples(ignored).isEmpty())
        assertEquals(SkillLevel.UNKNOWN, skill(ignored).level)
        assertNull(skill(ignored).successBps)
    }

    @Test
    fun `UNKNOWN until three samples from two families, and confidence is reported separately`() {
        val two = listOf(outcome(family = "f1"), outcome(family = "f2"))
        assertEquals(SkillLevel.UNKNOWN, skill(two).level)
        assertEquals(Confidence.LOW, skill(two).confidence)
        val three = two + outcome(family = "f2", day = 2)
        assertEquals(SkillLevel.PRACTICING, skill(three).level, "100% success but no independent Transfers yet")
        assertEquals(Confidence.LOW, skill(three).confidence, "3 samples is still low confidence")
        val many = (1..10).map { outcome(family = "f${it % 4}", day = it) }
        assertEquals(Confidence.HIGH, skill(many).confidence)
    }

    @Test
    fun `repeating the same family on the same day counts once, and a chain of linked Sessions counts once`() {
        val sameDay = List(5) { outcome(family = "f1", day = 1) }
        assertEquals(1, skill(sameDay).sampleCount, "repetition is not more evidence")
        val chain = listOf(outcome(family = "f1", day = 1, root = "chain"), outcome(family = "f2", day = 2, root = "chain", transfer = true))
        assertEquals(1, skill(chain).sampleCount, "a Transfer and its origin are correlated")
    }

    @Test
    fun `help lowers the weight and DEMONSTRATED needs two independent Transfers from different families`() {
        val guided = SkillPolicy.samples(listOf(outcome(exposure = Exposure.GUIDED, family = "g1"), outcome(exposure = Exposure.LIGHT_HINTS, family = "g2"), outcome(kind = "FLAG", mode = "CTF", family = "g3")))
        assertEquals(listOf(4_500, 10_500, 5_000), guided.map { it.weightBps })
        val strong = listOf(outcome(family = "f1"), outcome(family = "f2"), outcome(family = "f3", transfer = true), outcome(family = "f4", transfer = true))
        assertEquals(SkillLevel.DEMONSTRATED, skill(strong).level)
        val helped = listOf(outcome(family = "f1"), outcome(family = "f2"), outcome(family = "f3", transfer = true, exposure = Exposure.LIGHT_HINTS), outcome(family = "f4", transfer = true))
        assertEquals(SkillLevel.PRACTICING, skill(helped).level, "a hinted Transfer is not independent")
        val weak = listOf(outcome(verdict = "FAIL", family = "f1"), outcome(verdict = "FAIL", family = "f2"), outcome(family = "f3"))
        assertEquals(SkillLevel.DEVELOPING, skill(weak).level)
    }

    @Test
    fun `recommendations explain themselves, treat UNKNOWN as a gap, and skip families whose solution was seen`() {
        val skills = SkillPolicy.project(listOf(outcome(kind = "DETECTION", verdict = "FAIL", family = "a"), outcome(kind = "DETECTION", verdict = "FAIL", family = "b"), outcome(kind = "DETECTION", family = "b", day = 2)))
        fun candidate(id: String, family: String, tags: List<String>, modes: List<String> = listOf("PURPLE")) = Candidate("s-$id", "v-$id", "Scenario $id", family, modes, tags, 30)
        val ranked = RecommendationPolicy.rank(
            listOf(
                candidate("unknown", "new-1", listOf("AUTHORIZATION")), candidate("weak", "b", listOf("DETECTION")),
                candidate("seen", "spoiled", listOf("AUTHORIZATION")), candidate("other", "new-2", listOf("FORENSICS"), listOf("CTF")),
                candidate("fourth", "new-3", listOf("AUTHORIZATION"), listOf("CTF")),
            ),
            skills, playedFamilies = setOf("a", "b"), solutionExposedFamilies = setOf("spoiled"), guidedFamilies = emptySet(), preferredMode = "PURPLE",
        )
        assertEquals(3, ranked.size)
        assertTrue(ranked.none { it.candidate.family == "spoiled" })
        assertEquals("v-unknown", ranked.first().scenarioVersionId())
        assertTrue(Reason.EVIDENCE_GAP in ranked.first().reasons && Reason.NEW_FAMILY in ranked.first().reasons)
        val weak = RecommendationPolicy.rank(listOf(candidate("weak", "b", listOf("DETECTION"))), skills, setOf("a", "b"), emptySet(), emptySet(), null).single()
        assertEquals(0, weak.terms.getValue("novelty"))
        assertEquals(SkillLevel.DEVELOPING, skills.single { it.key == "DETECTION" }.level)
        assertEquals(6_667, weak.terms.getValue("lowIndependentSuccess"), "1 of 3 equally weighted samples passed")
        assertTrue(Reason.LOW_INDEPENDENT_SUCCESS in weak.reasons)
    }

    private fun Recommendation.scenarioVersionId() = candidate.scenarioVersionId
}
