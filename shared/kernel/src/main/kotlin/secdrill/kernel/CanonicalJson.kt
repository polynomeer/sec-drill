package secdrill.kernel

import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * RFC 8785 (JCS) canonical JSON for the value shapes contracts use (D-04, ADR 0003): objects, arrays, strings,
 * booleans, null and integers within ±(2^53 - 1). Non-integer numbers are rejected rather than approximated,
 * because JCS number formatting for them is easy to get subtly wrong across languages.
 *
 * Accepted Kotlin shapes: `Map<String, *>`, `List<*>`, `String`, `Boolean`, `null`, integral `Number`,
 * plus `UUID` and `Instant`, which encode as their canonical strings.
 */
object CanonicalJson {
    private const val MAX_SAFE = 9_007_199_254_740_991L

    fun encode(value: Any?): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is String -> string(value, out)
            is UUID -> string(value.toString(), out)
            is Instant -> string(Rfc3339.format(value), out)
            is Int, is Long, is Short, is Byte -> integer(BigInteger.valueOf((value as Number).toLong()), out)
            is BigInteger -> integer(value, out)
            is BigDecimal -> integer(
                runCatching { value.toBigIntegerExact() }.getOrElse { throw IllegalArgumentException("non-integer number") },
                out,
            )
            is Double, is Float -> {
                val number = (value as Number).toDouble()
                require(number.isFinite() && number == Math.rint(number)) { "non-integer number" }
                integer(BigDecimal(number).toBigIntegerExact(), out)
            }
            is Map<*, *> -> {
                // JCS orders members by their UTF-16 code units, which is exactly String.compareTo.
                val entries = value.entries.map { (key, item) ->
                    require(key is String) { "object keys must be strings" }
                    key to item
                }.sortedWith { a, b -> a.first.compareTo(b.first) }
                out.append('{')
                entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(',')
                    string(key, out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported JSON value type ${value::class.simpleName}")
        }
    }

    private fun integer(value: BigInteger, out: StringBuilder) {
        require(value.abs() <= BigInteger.valueOf(MAX_SAFE)) { "integer outside the interoperable range" }
        out.append(value.toString())
    }

    private fun string(value: String, out: StringBuilder) {
        out.append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append("\\u").append("%04x".format(char.code))
                char.isHighSurrogate() -> {
                    require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "lone surrogate" }
                    out.append(char).append(value[index + 1])
                    index++
                }
                char.isLowSurrogate() -> throw IllegalArgumentException("lone surrogate")
                else -> out.append(char)
            }
            index++
        }
        out.append('"')
    }
}

object Digests {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** SHA-256 of the RFC 8785 canonical UTF-8 encoding. Used for request digests and Evidence payload digests. */
    fun canonical(value: Any?): String = sha256Hex(CanonicalJson.encode(value).toByteArray(Charsets.UTF_8))
}

/** Evidence hash chain (14, ADR 0003). Each entry commits to the previous hash, so edits and gaps are detectable. */
object EvidenceChain {
    val GENESIS: String = "0".repeat(64)

    fun link(
        previousHash: String,
        sessionId: UUID,
        seq: Long,
        eventType: String,
        source: EvidenceSource,
        trustLevel: TrustLevel,
        occurredAt: Instant,
        payloadDigest: String,
    ): String = Digests.canonical(
        mapOf(
            "eventType" to eventType,
            "occurredAt" to occurredAt,
            "payloadDigest" to payloadDigest,
            "previousHash" to previousHash,
            "seq" to seq,
            "sessionId" to sessionId,
            "source" to source.name,
            "trustLevel" to trustLevel.name,
        ),
    )
}
