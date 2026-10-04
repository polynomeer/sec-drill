package secdrill.controlplane.evidence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import secdrill.kernel.Digests
import secdrill.kernel.EvidenceChain
import secdrill.kernel.EvidenceSource
import secdrill.kernel.TrustLevel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

data class LedgerProblem(val seq: Long, val reason: String)

data class LedgerVerification(val sessionId: UUID, val entries: Int, val lastSeq: Long, val problems: List<LedgerProblem>) {
    val intact: Boolean get() = problems.isEmpty()
}

/**
 * Recomputes a Session's ledger from stored rows (10, 22): payload digests, hash links, seq continuity from 1 and
 * the head. Detects edits, removed rows and gaps. It cannot detect a database superuser who rewrites the whole chain
 * consistently; signed checkpoints are a later control (10).
 */
@Component
class LedgerVerifier(private val jdbc: JdbcClient, private val json: JsonMapper) {
    fun verify(sessionId: UUID): LedgerVerification {
        val rows = jdbc.sql(
            """SELECT seq, event_type, source, trust_level, occurred_at, payload_digest, previous_hash, hash, safe_payload::text
               FROM evidence WHERE session_id = ? ORDER BY seq""",
        ).param(sessionId).query { rs, _ ->
            Row(
                rs.getLong(1), rs.getString(2), EvidenceSource.valueOf(rs.getString(3)), TrustLevel.valueOf(rs.getString(4)),
                rs.getObject(5, OffsetDateTime::class.java), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
            )
        }.list()
        val problems = mutableListOf<LedgerProblem>()
        var expectedPrevious = EvidenceChain.GENESIS
        var expectedSeq = 1L
        for (row in rows) {
            if (row.seq != expectedSeq) problems += LedgerProblem(expectedSeq, "gap: next stored seq is ${row.seq}")
            if (row.previousHash != expectedPrevious) problems += LedgerProblem(row.seq, "previous hash does not link")
            val digest = Digests.canonical(plain(json.readTree(row.safePayload)))
            if (digest != row.payloadDigest) problems += LedgerProblem(row.seq, "payload digest mismatch")
            val hash = EvidenceChain.link(row.previousHash, sessionId, row.seq, row.eventType, row.source, row.trust, row.occurredAt.toInstant(), row.payloadDigest)
            if (hash != row.hash) problems += LedgerProblem(row.seq, "hash mismatch")
            expectedPrevious = row.hash
            expectedSeq = row.seq + 1
        }
        val head = jdbc.sql("SELECT last_seq, last_hash FROM ledger_heads WHERE session_id = ?").param(sessionId)
            .query { rs, _ -> rs.getLong(1) to rs.getString(2) }.optional().orElse(null)
        val lastSeq = rows.lastOrNull()?.seq ?: 0
        when {
            head == null && rows.isNotEmpty() -> problems += LedgerProblem(lastSeq, "ledger head missing")
            head != null && (head.first != lastSeq || head.second != (rows.lastOrNull()?.hash ?: EvidenceChain.GENESIS)) ->
                problems += LedgerProblem(head.first, "head does not match the last entry (truncated or extended)")
        }
        return LedgerVerification(sessionId, rows.size, lastSeq, problems)
    }

    private fun plain(node: JsonNode): Any? = when {
        node.isObject -> node.properties().associate { it.key to plain(it.value) }
        node.isArray -> node.values().map(::plain)
        node.isString -> node.asString()
        node.isBoolean -> node.asBoolean()
        node.isNull -> null
        node.isIntegralNumber -> node.bigIntegerValue()
        else -> error("non-canonical value in stored payload")
    }

    private data class Row(
        val seq: Long, val eventType: String, val source: EvidenceSource, val trust: TrustLevel, val occurredAt: OffsetDateTime,
        val payloadDigest: String, val previousHash: String, val hash: String, val safePayload: String,
    )
}
