package secdrill.controlplane.evidence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import secdrill.kernel.Digests
import secdrill.kernel.EvidenceChain
import secdrill.kernel.EvidenceSource
import secdrill.kernel.TrustLevel
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AppendedEvidence(val evidenceId: UUID, val seq: Long, val hash: String)

/**
 * Appends to a Session's Evidence Ledger inside the caller's transaction (14). The Session's ledger head row is
 * locked, so concurrent appends get consecutive `seq` values and each entry links to the previous hash (ADR 0003).
 * `safePayload` must hold only identifiers and digests: no raw flags, source, tokens or PII (16).
 */
@Component
class LedgerAppender(private val jdbc: JdbcClient, private val json: JsonMapper, private val clock: Clock) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun append(
        sessionId: UUID,
        eventType: String,
        source: EvidenceSource,
        trustLevel: TrustLevel,
        safePayload: Map<String, Any?>,
    ): AppendedEvidence {
        jdbc.sql("INSERT INTO ledger_heads(session_id, last_seq, last_hash) VALUES (?, 0, ?) ON CONFLICT DO NOTHING")
            .params(sessionId, EvidenceChain.GENESIS).update()
        val (lastSeq, lastHash) = jdbc.sql("SELECT last_seq, last_hash FROM ledger_heads WHERE session_id = ? FOR UPDATE")
            .param(sessionId).query { rs, _ -> rs.getLong(1) to rs.getString(2) }.single()

        // PostgreSQL keeps microseconds; hash exactly what is stored so the chain can be re-verified (T09).
        val occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val seq = lastSeq + 1
        val payloadDigest = Digests.canonical(safePayload)
        val hash = EvidenceChain.link(lastHash, sessionId, seq, eventType, source, trustLevel, occurredAt, payloadDigest)
        val id = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO evidence(id, session_id, seq, event_type, source, trust_level, schema_version, occurred_at, ingested_at,
               payload_digest, previous_hash, hash, safe_payload) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?::jsonb)""",
        ).params(
            id, sessionId, seq, eventType, source.name, trustLevel.name, occurredAt.atOffset(ZoneOffset.UTC), occurredAt.atOffset(ZoneOffset.UTC),
            payloadDigest, lastHash, hash, json.writeValueAsString(safePayload),
        ).update()
        jdbc.sql("UPDATE ledger_heads SET last_seq = ?, last_hash = ? WHERE session_id = ?").params(seq, hash, sessionId).update()
        return AppendedEvidence(id, seq, hash)
    }
}
