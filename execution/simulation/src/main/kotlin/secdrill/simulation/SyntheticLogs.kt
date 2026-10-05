package secdrill.simulation

import java.util.SplittableRandom

data class Dataset(val events: List<LogEvent>, val truth: GroundTruth)

/**
 * Synthetic normalized logs for the tenant-orders detection drill (21, 23). Fully determined by (version, seed,
 * variant). Training and holdout use different seeds and route-name sets, so actor ids, route names and timing
 * differ while the behaviour stays equivalent:
 * - tenant users read their own tenant's resources
 * - one support user legitimately reads other tenants now and then (a single cross-tenant read is not an attack)
 * - attack episodes are bursts of successful cross-tenant order reads by one compromised user
 *
 * All identifiers are synthetic. The labels exist only in [GroundTruth].
 */
object SyntheticLogs {
    const val VERSION = "tenant-orders-logs/1"
    private val trainingRoutes = listOf("orders", "invoices", "profile")
    private val holdoutRoutes = listOf("orders-api", "billing", "account")

    enum class Variant { TRAINING, HOLDOUT }

    fun generate(seed: Long, variant: Variant, durationSeconds: Long = 3600, episodes: Int = 3): Dataset {
        val random = SplittableRandom(seed xor (if (variant == Variant.TRAINING) 0x5EC0L else 0x401DL))
        val routes = if (variant == Variant.TRAINING) trainingRoutes else holdoutRoutes
        fun id(prefix: String) = prefix + "-" + (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789"[random.nextInt(31)] }.joinToString("")
        val tenants = List(3) { id("t") }
        val users = List(9) { index -> id("u") to tenants[index % tenants.size] }
        val support = id("s") to tenants[0]
        val events = mutableListOf<LogEvent>()
        var counter = 0
        fun add(time: Long, actor: Pair<String, String>, resourceTenant: String, route: String, status: Int) {
            events += LogEvent("e-${variant.name.lowercase()}-${counter++}", time, "resource.read", actor.first, actor.second, resourceTenant, status, route)
        }

        users.forEach { user ->
            var t = random.nextLong(30)
            while (t < durationSeconds) {
                add(t, user, user.second, routes[random.nextInt(routes.size)], if (random.nextInt(20) == 0) 404 else 200)
                t += 20 + random.nextLong(40)
            }
        }
        var t = random.nextLong(120)
        while (t < durationSeconds) {
            add(t, support, tenants[1 + random.nextInt(tenants.size - 1)], routes[0], 200)
            t += 240 + random.nextLong(240)
        }

        // Episodes: one compromised user, non-overlapping bursts spread over the run.
        val attacker = users[random.nextInt(users.size)]
        val slot = durationSeconds / (episodes + 1)
        val truthEpisodes = (1..episodes).map { n ->
            val start = n * slot + random.nextLong(slot / 4)
            val reads = 8 + random.nextInt(8)
            var time = start
            repeat(reads) {
                add(time, attacker, tenants.first { it != attacker.second }, routes[0], 200)
                time += 2 + random.nextLong(6)
            }
            Episode("ep-$n", start, start + 120)
        }

        // Normal windows: 300 s per non-attacking actor, plus the attacker's windows clear of its episodes.
        val windows = (users.map { it.first } + support.first).flatMap { actor ->
            (0 until durationSeconds step 300).map { start -> NormalWindow(actor, start, start + 299) }
                .filter { window -> actor != attacker.first || truthEpisodes.none { it.firstMaliciousTime <= window.end && window.start <= it.windowEnd } }
        }
        return Dataset(events.sortedWith(compareBy({ it.time }, { it.eventId })), GroundTruth(truthEpisodes, windows))
    }
}
