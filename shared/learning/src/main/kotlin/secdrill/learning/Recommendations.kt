package secdrill.learning

/** A published scenario the learner could take next (public manifest fields only). */
data class Candidate(
    val scenarioId: String,
    val scenarioVersionId: String,
    val title: String,
    val family: String,
    val modes: List<String>,
    val competencyTags: List<String>,
    val estimatedMinutes: Int,
)

enum class Reason { EVIDENCE_GAP, LOW_INDEPENDENT_SUCCESS, NEW_FAMILY, PREFERRED_MODE, TRANSFER_AFTER_HELP }

data class Recommendation(
    val candidate: Candidate,
    val scoreBps: Int,
    val reasons: List<Reason>,
    /** The four weighted terms, each 0..10000, so a learner can see why (23). */
    val terms: Map<String, Int>,
)

/**
 * Recommendation policy v1 (23): `0.4 × evidence gap + 0.3 × low recent independent success + 0.2 × family novelty
 * + 0.1 × preference`, each term in 0..1 (here basis points). UNKNOWN is an evidence gap, never low success.
 * Families whose solution the learner has seen are not offered. Top 3, each with its reasons.
 */
object RecommendationPolicy {
    const val VERSION = "recommend-v1"

    fun rank(
        candidates: List<Candidate>,
        skills: List<SkillView>,
        playedFamilies: Set<String>,
        solutionExposedFamilies: Set<String>,
        guidedFamilies: Set<String>,
        preferredMode: String?,
    ): List<Recommendation> {
        val byKey = skills.associateBy { it.key }
        return candidates
            .filter { it.family !in solutionExposedFamilies && it.modes.any { mode -> mode in setOf("CTF", "WARGAME", "PURPLE") } }
            .map { candidate ->
                val tags = candidate.competencyTags.filter { it in byKey }
                fun mean(values: List<Int>) = if (values.isEmpty()) 0 else values.sum() / values.size
                val gap = mean(tags.map { tag ->
                    val skill = byKey.getValue(tag)
                    when {
                        skill.level == SkillLevel.UNKNOWN -> 10_000
                        skill.confidence == Confidence.LOW -> 6_000
                        skill.confidence == Confidence.MEDIUM -> 3_000
                        else -> 1_000
                    }
                })
                // Only measured skills can have low success; UNKNOWN contributes nothing here.
                val lowSuccess = mean(tags.mapNotNull { tag -> byKey.getValue(tag).takeIf { it.level != SkillLevel.UNKNOWN }?.successBps?.let { 10_000 - it } })
                val novelty = if (candidate.family in playedFamilies) 0 else 10_000
                val preference = if (preferredMode != null && preferredMode in candidate.modes) 10_000 else 0
                val score = (4 * gap + 3 * lowSuccess + 2 * novelty + preference) / 10
                val reasons = buildList {
                    if (gap >= 6_000) add(Reason.EVIDENCE_GAP)
                    if (lowSuccess >= 5_000) add(Reason.LOW_INDEPENDENT_SUCCESS)
                    if (novelty > 0) add(Reason.NEW_FAMILY)
                    if (novelty > 0 && guidedFamilies.isNotEmpty()) add(Reason.TRANSFER_AFTER_HELP)
                    if (preference > 0) add(Reason.PREFERRED_MODE)
                }
                Recommendation(candidate, score, reasons, mapOf("evidenceGap" to gap, "lowIndependentSuccess" to lowSuccess, "novelty" to novelty, "preference" to preference))
            }
            .sortedWith(compareByDescending<Recommendation> { it.scoreBps }.thenBy { it.candidate.title }.thenBy { it.candidate.scenarioVersionId })
            .take(3)
    }
}
