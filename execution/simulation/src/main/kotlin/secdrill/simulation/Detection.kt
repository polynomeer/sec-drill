package secdrill.simulation

import tools.jackson.databind.JsonNode

/**
 * Detection rule DSL (21, OpenAPI `DetectionNode`). A bounded JSON AST: eq/neq against a literal or another field,
 * and/or, and count_gte over a window. No regex, SQL, functions or shell. Limits: depth 8, 64 nodes, window 300 s,
 * 5 s evaluation budget.
 */
sealed interface Rule {
    data class Compare(val negate: Boolean, val field: Field, val value: Any?, val compareField: Field?) : Rule
    data class Logical(val all: Boolean, val children: List<Rule>) : Rule
    data class CountAtLeast(val predicate: Rule, val count: Int, val windowSeconds: Int) : Rule
}

/** Normalized server log fields a rule may read. Ground-truth labels are not fields and never reach a rule. */
enum class Field(val wire: String) {
    EVENT_TYPE("eventType"), ACTOR_ID("actorId"), TENANT_ID("tenantId"), RESOURCE_TENANT_ID("resourceTenantId"),
    STATUS("status"), ROUTE_GROUP("routeGroup");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.wire == name }
    }
}

/** One normalized log event. Deliberately has no attack label. */
data class LogEvent(
    val eventId: String,
    val time: Long,
    val eventType: String,
    val actorId: String?,
    val tenantId: String?,
    val resourceTenantId: String?,
    val status: Int?,
    val routeGroup: String?,
) {
    fun get(field: Field): Any? = when (field) {
        Field.EVENT_TYPE -> eventType
        Field.ACTOR_ID -> actorId
        Field.TENANT_ID -> tenantId
        Field.RESOURCE_TENANT_ID -> resourceTenantId
        Field.STATUS -> status
        Field.ROUTE_GROUP -> routeGroup
    }
}

class RuleInvalid(val problems: List<String>) : RuntimeException(problems.joinToString("; "))

/** The rule exceeded its evaluation budget: a user resource limit (20), not a platform error. */
class RuleBudgetExceeded : RuntimeException("rule evaluation exceeded its budget")

object RuleParser {
    const val MAX_DEPTH = 8
    const val MAX_NODES = 64
    const val MAX_WINDOW_SECONDS = 300

    fun parse(node: JsonNode): Rule {
        val problems = mutableListOf<String>()
        var nodes = 0
        fun walk(n: JsonNode, depth: Int, path: String, insideCount: Boolean): Rule? {
            nodes++
            if (depth > MAX_DEPTH) { problems += "$path: deeper than $MAX_DEPTH levels"; return null }
            if (!n.isObject) { problems += "$path: must be an object"; return null }
            fun unknown(allowed: Set<String>) = (n.propertyNames().toSet() - allowed).forEach { problems += "$path.$it: unknown field" }
            return when (val op = n["op"]?.takeIf { it.isString }?.asString()) {
                "eq", "neq" -> {
                    val field = Field.of(n["field"]?.asString()) ?: run { problems += "$path.field: unknown field name"; null }
                    val hasValue = n.has("value")
                    val hasCompare = n.has("compareField")
                    if (hasValue == hasCompare) problems += "$path: exactly one of value or compareField"
                    unknown(setOf("op", "field", if (hasValue) "value" else "compareField"))
                    val compare = if (hasCompare) Field.of(n["compareField"]?.asString()) ?: run { problems += "$path.compareField: unknown field name"; null } else null
                    val value: Any? = if (hasValue) n["value"].let {
                        when {
                            it.isString && it.asString().length <= 256 -> it.asString()
                            it.isIntegralNumber && it.canConvertToInt() -> it.asInt()
                            else -> { problems += "$path.value: must be a string (at most 256 characters) or an integer"; null }
                        }
                    } else null
                    if (field == null || (hasCompare && compare == null)) null else Rule.Compare(op == "neq", field, value, compare)
                }
                "and", "or" -> {
                    unknown(setOf("op", "children"))
                    val children = n["children"]?.takeIf { it.isArray }?.values()?.toList()
                    if (children == null || children.size !in 2..8) { problems += "$path.children: 2 to 8 rules"; return null }
                    val parsed = children.mapIndexed { i, child -> walk(child, depth + 1, "$path.children[$i]", insideCount) }
                    if (parsed.any { it == null }) null else Rule.Logical(op == "and", parsed.filterNotNull())
                }
                "count_gte" -> {
                    unknown(setOf("op", "predicate", "count", "windowSeconds"))
                    // A count inside a count would need nested windows; MVP evaluation stays linear in the events.
                    if (insideCount) problems += "$path: count_gte cannot be nested inside another count_gte"
                    val count = n["count"]?.takeIf { it.isIntegralNumber }?.asInt()
                    val window = n["windowSeconds"]?.takeIf { it.isIntegralNumber }?.asInt()
                    if (count == null || count !in 1..10_000) problems += "$path.count: 1 to 10000"
                    if (window == null || window !in 1..MAX_WINDOW_SECONDS) problems += "$path.windowSeconds: 1 to $MAX_WINDOW_SECONDS"
                    val predicate = n["predicate"]?.let { walk(it, depth + 1, "$path.predicate", true) } ?: run { problems += "$path.predicate: required"; null }
                    if (predicate == null || count == null || window == null) null else Rule.CountAtLeast(predicate, count, window)
                }
                else -> { problems += "$path.op: unknown operator"; null }
            }
        }
        val rule = walk(node, 1, "rule", false)
        if (nodes > MAX_NODES) problems += "rule: more than $MAX_NODES nodes"
        if (problems.isNotEmpty() || rule == null) throw RuleInvalid(problems.distinct().take(50))
        return rule
    }
}

data class Alert(val eventId: String, val time: Long, val actorId: String?)

/**
 * Evaluates a rule over events in event-time order and returns the events at which it fires. count_gte counts
 * distinct event ids matching its predicate for the same actorId + routeGroup within (t - window, t].
 * Comparisons with a missing field are false.
 */
class RuleEvaluator(private val budgetNanos: Long = 5_000_000_000L, private val nanoTime: () -> Long = System::nanoTime) {
    fun alerts(rule: Rule, events: List<LogEvent>): List<Alert> {
        val deadline = nanoTime() + budgetNanos
        val ordered = events.distinctBy { it.eventId }.sortedWith(compareBy({ it.time }, { it.eventId }))
        // Keyed by node identity: two equal-looking count nodes keep separate windows.
        val windows = java.util.IdentityHashMap<Rule.CountAtLeast, HashMap<Pair<String?, String?>, ArrayDeque<LogEvent>>>()
        var steps = 0L
        fun check() {
            if (++steps % 1024 == 0L && nanoTime() > deadline) throw RuleBudgetExceeded()
        }
        fun eval(r: Rule, event: LogEvent): Boolean {
            check()
            return when (r) {
                is Rule.Compare -> {
                    val left = event.get(r.field) ?: return false
                    val right = (if (r.compareField != null) event.get(r.compareField) else r.value) ?: return false
                    (left == right) != r.negate
                }
                is Rule.Logical -> if (r.all) r.children.all { eval(it, event) } else r.children.any { eval(it, event) }
                is Rule.CountAtLeast -> {
                    val key = event.actorId to event.routeGroup
                    val window = windows.getOrPut(r) { HashMap() }.getOrPut(key) { ArrayDeque() }
                    // Each event is offered once per count node, in time order (see the loop below).
                    if (eval(r.predicate, event)) window.addLast(event)
                    while (window.isNotEmpty() && window.first().time <= event.time - r.windowSeconds) window.removeFirst()
                    window.size >= r.count
                }
            }
        }
        val counts = mutableListOf<Rule.CountAtLeast>()
        fun collect(r: Rule) {
            when (r) {
                is Rule.CountAtLeast -> counts += r
                is Rule.Logical -> r.children.forEach(::collect)
                is Rule.Compare -> Unit
            }
        }
        collect(rule)
        return ordered.mapNotNull { event ->
            // Window state must see every event even when an `and`/`or` short-circuits, so update counts first.
            val counted = java.util.IdentityHashMap<Rule.CountAtLeast, Boolean>().also { map -> counts.forEach { map[it] = eval(it, event) } }
            fun final(r: Rule): Boolean = when (r) {
                is Rule.CountAtLeast -> counted.getValue(r)
                is Rule.Logical -> if (r.all) r.children.all(::final) else r.children.any(::final)
                is Rule.Compare -> eval(r, event)
            }
            if (final(rule)) Alert(event.eventId, event.time, event.actorId) else null
        }
    }
}
