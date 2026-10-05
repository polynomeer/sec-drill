package secdrill.controlplane.evidence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.access.OwnedResource
import secdrill.controlplane.access.OwnershipGuard
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.FieldError
import secdrill.kernel.Rfc3339
import secdrill.kernel.TrustLevel
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** OpenAPI `Evidence`; `summary` is the ledger's safe payload, never raw content. */
data class EvidenceView(
    val id: UUID,
    val sessionId: UUID,
    val seq: Long,
    val type: String,
    val trustLevel: TrustLevel,
    val occurredAt: String,
    val ingestedAt: String,
    val payloadDigest: String,
    val hash: String,
    val summary: JsonNode,
)

data class EvidencePage(val items: List<EvidenceView>, val nextSeq: Long, val hasMore: Boolean)

/** `GET /v1/sessions/{id}/evidence` (15): seq cursor paging; other owners' Sessions are 404. */
@RestController
class EvidenceController(private val jdbc: JdbcClient, private val guard: OwnershipGuard, private val json: JsonMapper) {
    @GetMapping("/v1/sessions/{id}/evidence")
    fun list(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") afterSeq: Long,
        @RequestParam(defaultValue = "50") limit: Int,
    ): EvidencePage {
        val errors = buildList {
            if (afterSeq < 0) add(FieldError("afterSeq", "must be >= 0"))
            if (limit !in 1..100) add(FieldError("limit", "must be between 1 and 100"))
        }
        if (errors.isNotEmpty()) throw ApiException(ErrorCode.VALIDATION_FAILED, "Invalid paging", ErrorDetails(fieldErrors = errors))
        guard.requireOwned(principal, OwnedResource.SESSION, id)
        return page(id, afterSeq, limit)
    }

    fun page(id: UUID, afterSeq: Long, limit: Int): EvidencePage {
        val rows = jdbc.sql(
            """SELECT id, seq, event_type, trust_level, occurred_at, ingested_at, payload_digest, hash, safe_payload::text
               FROM evidence WHERE session_id = ? AND seq > ? ORDER BY seq LIMIT ?""",
        ).params(id, afterSeq, limit + 1).query { rs, _ ->
            EvidenceView(
                rs.getObject(1, UUID::class.java), id, rs.getLong(2), rs.getString(3), TrustLevel.valueOf(rs.getString(4)),
                Rfc3339.format(rs.getObject(5, OffsetDateTime::class.java).toInstant()),
                Rfc3339.format(rs.getObject(6, OffsetDateTime::class.java).toInstant()),
                rs.getString(7), rs.getString(8), json.readTree(rs.getString(9)),
            )
        }.list()
        val page = rows.take(limit)
        return EvidencePage(page, page.lastOrNull()?.seq ?: afterSeq, rows.size > limit)
    }
}

/**
 * `GET /v1/sessions/{id}/stream` (15, 07): SSE with `id` = evidence seq and `event: evidence`. A reconnecting client
 * sends `Last-Event-ID` and receives only what it missed, so the cursor survives reconnects. Polls the ledger once a
 * second; the connection closes after [STREAM_TTL_MILLIS] and the client reconnects with its cursor.
 */
@RestController
class EvidenceStreamController(private val evidence: EvidenceController, private val guard: OwnershipGuard) {
    companion object {
        const val STREAM_TTL_MILLIS = 300_000L
    }

    @GetMapping("/v1/sessions/{id}/stream", produces = ["text/event-stream"])
    fun stream(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @PathVariable id: UUID,
        @org.springframework.web.bind.annotation.RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        val cursor = lastEventId?.let { it.toLongOrNull()?.takeIf { seq -> seq >= 0 } ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Last-Event-ID must be a sequence number") } ?: 0L
        guard.requireOwned(principal, OwnedResource.SESSION, id)
        val emitter = org.springframework.web.servlet.mvc.method.annotation.SseEmitter(STREAM_TTL_MILLIS)
        Thread.ofVirtual().name("evidence-stream-$id").start {
            var seq = cursor
            val deadline = System.currentTimeMillis() + STREAM_TTL_MILLIS - 1_000
            try {
                while (System.currentTimeMillis() < deadline) {
                    val page = evidence.page(id, seq, 100)
                    page.items.forEach { item ->
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().id(item.seq.toString()).name("evidence").data(item))
                        seq = item.seq
                    }
                    if (!page.hasMore) Thread.sleep(1_000)
                }
                emitter.complete()
            } catch (gone: Exception) {
                // Client went away or the request ended; it resumes from its Last-Event-ID.
                runCatching { emitter.completeWithError(gone) }
            }
        }
        return emitter
    }
}
