package secdrill.simulation

/**
 * Ground truth for a dataset (21). Kept apart from [LogEvent]s so a rule can never read it.
 * An episode's window runs from its first malicious event to the end of its valid detection window.
 */
data class Episode(val episodeId: String, val firstMaliciousTime: Long, val windowEnd: Long)

/** A predefined normal actor/window: no alert in it is a true negative, any alert in it is a false positive. */
data class NormalWindow(val actorId: String, val start: Long, val end: Long)

data class GroundTruth(val episodes: List<Episode>, val normalWindows: List<NormalWindow>)

/** A ratio in basis points (0..10000), or N/A when its denominator is zero. Never shown as 0 % or 100 % when N/A. */
@JvmInline
value class Ratio private constructor(private val raw: Int) {
    val bps: Int? get() = raw.takeIf { it >= 0 }
    val isNa: Boolean get() = raw < 0

    companion object {
        val NA = Ratio(-1)
        fun of(numerator: Long, denominator: Long): Ratio = if (denominator == 0L) NA else Ratio(((numerator * 10_000 + denominator / 2) / denominator).toInt())
        fun ofBps(bps: Int) = Ratio(bps.coerceIn(0, 10_000))
    }

    override fun toString() = bps?.let { "${it}bps" } ?: "N/A"
}

data class DetectionMetrics(
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val trueNegatives: Int,
    val precision: Ratio,
    val recall: Ratio,
    val f1: Ratio,
    val falsePositiveRate: Ratio,
    /** Seconds from each detected episode's first malicious event to its first alert. Missed episodes are FNs, not latencies. */
    val latencies: List<Long>,
    val p95LatencySeconds: Long?,
    val episodes: Int,
    val normalWindows: Int,
)

/**
 * Episode-based scoring (21):
 * - TP: an episode with at least one alert inside its window (only the first counts; more alerts add nothing)
 * - FN: an episode without one
 * - FP: a normal window with at least one alert, plus each alert outside every episode and normal window
 *   (counted one by one so an alert flood cannot hide in unlabelled time)
 * - TN: a normal window without alerts
 */
object DetectionScorer {
    fun score(alerts: List<Alert>, truth: GroundTruth): DetectionMetrics {
        val latencies = mutableListOf<Long>()
        var tp = 0
        truth.episodes.forEach { episode ->
            val first = alerts.filter { it.time in episode.firstMaliciousTime..episode.windowEnd }.minByOrNull { it.time }
            if (first != null) {
                tp++
                latencies += first.time - episode.firstMaliciousTime
            }
        }
        val fn = truth.episodes.size - tp
        val outsideEpisodes = alerts.filter { alert -> truth.episodes.none { alert.time in it.firstMaliciousTime..it.windowEnd } }
        val flagged = truth.normalWindows.filter { window -> outsideEpisodes.any { it.actorId == window.actorId && it.time in window.start..window.end } }
        val unscoped = outsideEpisodes.count { alert -> truth.normalWindows.none { alert.actorId == it.actorId && alert.time in it.start..it.end } }
        val fp = flagged.size + unscoped
        val tn = truth.normalWindows.size - flagged.size
        val precision = Ratio.of(tp.toLong(), (tp + fp).toLong())
        val recall = Ratio.of(tp.toLong(), (tp + fn).toLong())
        val f1 = when {
            precision.isNa || recall.isNa -> Ratio.NA
            else -> {
                val p = precision.bps!!.toLong()
                val r = recall.bps!!.toLong()
                if (p + r == 0L) Ratio.NA else Ratio.of(2 * p * r, (p + r) * 10_000)
            }
        }
        return DetectionMetrics(tp, fp, fn, tn, precision, recall, f1, Ratio.of(fp.toLong(), (fp + tn).toLong()), latencies.sorted(),
            p95(latencies), truth.episodes.size, truth.normalWindows.size)
    }

    /** Nearest-rank p95; null (N/A) without detected episodes. */
    private fun p95(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = Math.ceil(0.95 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}

/** Content thresholds (oracle `detection`), in basis points and seconds. */
data class DetectionThresholds(val minRecallBps: Int, val minPrecisionBps: Int, val maxP95LatencySeconds: Long)

enum class Gate { PASS, FAIL, INCONCLUSIVE }

object DetectionGate {
    /** N/A metrics cannot pass: a dataset without episodes or alerts proves nothing either way. */
    fun of(metrics: DetectionMetrics, thresholds: DetectionThresholds): Gate {
        val recall = metrics.recall.bps ?: return Gate.INCONCLUSIVE
        if (metrics.episodes == 0) return Gate.INCONCLUSIVE
        val precision = metrics.precision.bps ?: return Gate.FAIL // no alerts at all: every episode was missed
        val latency = metrics.p95LatencySeconds ?: return Gate.FAIL
        return if (recall >= thresholds.minRecallBps && precision >= thresholds.minPrecisionBps && latency <= thresholds.maxP95LatencySeconds) Gate.PASS else Gate.FAIL
    }
}
