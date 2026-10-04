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
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AppendedEvidence(val evidenceId: UUID, val seq: Long, val hash: String, val duplicate: Boolean = false)

/**
 * Appends to a Session's Evidence Ledger inside the caller's transaction (10, 14, ADR 0003/0005).
 *
 * - The Session's ledger head row is locked, so concurrent appends get consecutive `seq` values and each entry
 *   links to the previous hash.
 * - `occurredAt` is when the fact happened (e.g. a late collector observation); `seq` is the server's order.
 * - `sourceEventId` makes the append idempotent: the same event recorded again returns the existing entry.
 * - `safePayload` holds identifiers and digests only; raw content goes to a private Artifact via [artifactId].
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
        occurredAt: Instant? = null,
        sourceEventId: UUID? = null,
        artifactId: UUID? = null,
    ): AppendedEvidence {
        require(EvidencePolicy.permits(source, trustLevel)) { "$source may not record $trustLevel evidence" }
        EvidencePolicy.requireSafePayload(safePayload)

        jdbc.sql("INSERT INTO ledger_heads(session_id, last_seq, last_hash) VALUES (?, 0, ?) ON CONFLICT DO NOTHING")
            .params(sessionId, EvidenceChain.GENESIS).update()
        val (lastSeq, lastHash) = jdbc.sql("SELECT last_seq, last_hash FROM ledger_heads WHERE session_id = ? FOR UPDATE")
            .param(sessionId).query { rs, _ -> rs.getLong(1) to rs.getString(2) }.single()

        // Checked under the head lock, so two deliveries of one event cannot both append.
        if (sourceEventId != null) {
            jdbc.sql("SELECT id, seq, hash FROM evidence WHERE session_id = ? AND source_event_id = ?").params(sessionId, sourceEventId)
                .query { rs, _ -> AppendedEvidence(rs.getObject(1, UUID::class.java), rs.getLong(2), rs.getString(3), duplicate = true) }
                .optional().orElse(null)?.let { return it }
        }

        // PostgreSQL keeps microseconds; hash exactly what is stored so the chain can be re-verified.
        val ingestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val observedAt = (occurredAt ?: ingestedAt).truncatedTo(ChronoUnit.MICROS)
        val seq = lastSeq + 1
        val payloadDigest = Digests.canonical(safePayload)
        val hash = EvidenceChain.link(lastHash, sessionId, seq, eventType, source, trustLevel, observedAt, payloadDigest)
        val id = UUID.randomUUID()
        jdbc.sql(
            """INSERT INTO evidence(id, session_id, seq, event_type, source, trust_level, schema_version, occurred_at, ingested_at,
               artifact_id, payload_digest, previous_hash, hash, safe_payload, source_event_id)
               VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
        ).params(
            listOf(
                id, sessionId, seq, eventType, source.name, trustLevel.name, observedAt.atOffset(ZoneOffset.UTC), ingestedAt.atOffset(ZoneOffset.UTC),
                artifactId, payloadDigest, lastHash, hash, json.writeValueAsString(safePayload), sourceEventId,
            ),
        ).update()
        jdbc.sql("UPDATE ledger_heads SET last_seq = ?, last_hash = ? WHERE session_id = ?").params(seq, hash, sessionId).update()
        return AppendedEvidence(id, seq, hash)
    }
}
