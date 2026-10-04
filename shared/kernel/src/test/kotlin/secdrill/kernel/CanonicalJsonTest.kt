package secdrill.kernel

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Kotlin side of the shared RFC 8785 vectors; scripts/check.py verifies the same file in Python. */
class CanonicalJsonTest {
    private val vectors: JsonNode = JsonMapper.builder().build()
        .readTree(Path.of(System.getProperty("secdrill.contracts.dir"), "fixtures/canonical.json").toFile())

    private fun plain(node: JsonNode): Any? = when {
        node.isObject -> node.properties().associate { it.key to plain(it.value) }
        node.isArray -> node.values().map(::plain)
        node.isString -> node.asString()
        node.isBoolean -> node.asBoolean()
        node.isNull -> null
        node.isIntegralNumber -> node.bigIntegerValue()
        else -> error("unexpected node $node")
    }

    @Test
    fun `canonical encoding and digests match the shared vectors`() {
        vectors["cases"].values().forEachIndexed { index, case ->
            assertEquals(case["canonical"].asString(), CanonicalJson.encode(plain(case["input"])), "case $index")
            assertEquals(case["sha256"].asString(), Digests.canonical(plain(case["input"])), "case $index")
        }
    }

    @Test
    fun `evidence chain link matches the shared vector`() {
        val fields = vectors["evidenceChain"]["fields"]
        val hash = EvidenceChain.link(
            previousHash = fields["previousHash"].asString(),
            sessionId = UUID.fromString(fields["sessionId"].asString()),
            seq = fields["seq"].asLong(),
            eventType = fields["eventType"].asString(),
            source = EvidenceSource.valueOf(fields["source"].asString()),
            trustLevel = TrustLevel.valueOf(fields["trustLevel"].asString()),
            occurredAt = Instant.parse(fields["occurredAt"].asString()),
            payloadDigest = fields["payloadDigest"].asString(),
        )
        assertEquals(vectors["evidenceChain"]["hash"].asString(), hash)
        assertEquals("0".repeat(64), EvidenceChain.GENESIS)
    }

    @Test
    fun `member order does not change the digest`() {
        assertEquals(Digests.canonical(mapOf("a" to 1, "b" to 2)), Digests.canonical(linkedMapOf("b" to 2, "a" to 1)))
    }

    @Test
    fun `values outside the contract subset are rejected`() {
        listOf<Any?>(1.5, Double.NaN, 9_007_199_254_740_992L, "\uD800", mapOf(1 to "x"), Any())
            .forEach { assertFailsWith<IllegalArgumentException>("$it") { CanonicalJson.encode(it) } }
    }
}
