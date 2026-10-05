package secdrill.learning

import java.time.LocalDate

/** Competency axes (10, taxonomy v1). Changing a key needs a migration map. */
object Taxonomy {
    const val VERSION = "taxonomy-v1"
    val KEYS = listOf(
        "AUTHORIZATION", "INPUT_BOUNDARY", "TOKEN_SECURITY", "ATTACK_REASONING", "OBSERVATION", "DETECTION", "RESPONSE", "SECURE_PATCHING", "FORENSICS",
    )
}

/** How much help a Session received, including what its parent Sessions received (help propagates to follow-ups). */
enum class Exposure { INDEPENDENT, LIGHT_HINTS, GUIDED, SOLUTION_EXPOSED }

/**
 * One graded result, as the projection sees it. Built from active evaluations only (a re-grade replaces the
 * revision it supersedes, never adds a second sample).
 */
data class GradedOutcome(
    val evaluationId: String,
    val evidenceIds: List<String>,
    val submissionKind: String,
    val mode: String,
    val verdict: String,
    val demo: Boolean,
    val family: String,
    val day: LocalDate,
    val exposure: Exposure,
    /** A follow-up Session (has a parent) in a different scenario family than its parent: a Transfer. */
    val transfer: Boolean,
    /** Root of the parent chain: outcomes in one chain are correlated and never count as independent samples. */
    val correlationRoot: String,
)

data class SkillSample(val competency: String, val family: String, val weightBps: Int, val success: Boolean, val independentTransfer: Boolean, val outcome: GradedOutcome)

enum class SkillLevel { UNKNOWN, DEVELOPING, PRACTICING, DEMONSTRATED }
enum class Confidence { LOW, MEDIUM, HIGH }

data class SkillView(
    val key: String,
    val level: SkillLevel,
    val confidence: Confidence,
    val sampleCount: Int,
    val familyCount: Int,
    /** Weighted success rate in basis points, or null with no samples. Not a probability of mastery (10). */
    val successBps: Int?,
    val evidenceIds: List<String>,
)

/**
 * Skill projection policy v1 (10). A product heuristic to calibrate in the pilot, not a psychometric model:
 * - excluded: SYSTEM_ERROR, demo results (fake worker or unverified isolation), wrong flags, postmortems (experimental)
 * - base weight: CTF objective 0.5, Wargame proof 1.0, Purple gate 1.5, Transfer without help 2.0
 * - help multiplier: light hints (H1-H2) 0.7, guided (H3-H4) or solution 0.3
 * - one sample per family, competency and day (the strongest), and one per correlated chain and competency
 * - level UNKNOWN below 3 samples or 2 families; DEMONSTRATED also needs 2 independent Transfers from 2 families
 * - confidence is separate: LOW (<5 or <3 families), MEDIUM (5-9 and >=3), HIGH (>=10 and >=4)
 */
object SkillPolicy {
    const val VERSION = "skill-v1"

    private val competencyOf = mapOf("FLAG" to "ATTACK_REASONING", "OBJECTIVE" to "ATTACK_REASONING", "PATCH" to "SECURE_PATCHING", "DETECTION" to "DETECTION")

    fun samples(outcomes: List<GradedOutcome>): List<SkillSample> {
        val eligible = outcomes.filter { o ->
            o.verdict != "SYSTEM_ERROR" && !o.demo && o.submissionKind in competencyOf && !(o.submissionKind == "FLAG" && o.verdict == "FAIL")
        }
        val weighted = eligible.map { o ->
            val independentTransfer = o.transfer && o.exposure == Exposure.INDEPENDENT
            val base = when {
                independentTransfer -> 20_000
                o.mode == "PURPLE" -> 15_000
                o.mode == "WARGAME" -> 10_000
                else -> 5_000
            }
            val help = when (o.exposure) {
                Exposure.INDEPENDENT -> 10_000
                Exposure.LIGHT_HINTS -> 7_000
                Exposure.GUIDED, Exposure.SOLUTION_EXPOSED -> 3_000
            }
            SkillSample(competencyOf.getValue(o.submissionKind), o.family, base * help / 10_000, o.verdict == "PASS", independentTransfer, o)
        }
        fun strongest(group: List<SkillSample>) = group.maxWith(compareBy<SkillSample> { it.weightBps }.thenBy { it.success }.thenBy { it.outcome.evaluationId })
        return weighted
            .groupBy { Triple(it.family, it.competency, it.outcome.day) }.values.map(::strongest)
            .groupBy { it.outcome.correlationRoot to it.competency }.values.map(::strongest)
    }

    fun project(outcomes: List<GradedOutcome>): List<SkillView> {
        val byCompetency = samples(outcomes).groupBy { it.competency }
        return Taxonomy.KEYS.map { key ->
            val samples = byCompetency[key].orEmpty()
            val families = samples.map { it.family }.toSet().size
            val totalWeight = samples.sumOf { it.weightBps.toLong() }
            val success = if (totalWeight == 0L) null else (samples.filter { it.success }.sumOf { it.weightBps.toLong() } * 10_000 / totalWeight).toInt()
            val transfers = samples.filter { it.independentTransfer && it.success }.map { it.family }.toSet().size
            val level = when {
                samples.size < 3 || families < 2 || success == null -> SkillLevel.UNKNOWN
                success < 5_000 -> SkillLevel.DEVELOPING
                success < 8_000 || transfers < 2 -> SkillLevel.PRACTICING
                else -> SkillLevel.DEMONSTRATED
            }
            val confidence = when {
                samples.size >= 10 && families >= 4 -> Confidence.HIGH
                samples.size >= 5 && families >= 3 -> Confidence.MEDIUM
                else -> Confidence.LOW
            }
            SkillView(key, level, confidence, samples.size, families, success, samples.flatMap { it.outcome.evidenceIds }.distinct())
        }
    }
}
