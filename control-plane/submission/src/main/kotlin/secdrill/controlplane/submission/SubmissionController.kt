package secdrill.controlplane.submission

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import secdrill.controlplane.identity.LearnerPrincipal
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import secdrill.kernel.Uuids
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** `POST /v1/sessions/{id}/submissions` (15). Origin and CSRF are enforced by the identity filters. */
@RestController
class SubmissionController(private val submissions: SubmissionService, private val json: JsonMapper) {
    @PostMapping("/v1/sessions/{id}/submissions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun submit(
        @AuthenticationPrincipal principal: LearnerPrincipal,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: ByteArray,
    ): ResponseEntity<String> {
        val key = idempotencyKey?.let { runCatching { Uuids.parse(it) }.getOrNull() }
            ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Idempotency-Key header must be a UUID")
        if (body.size > SubmissionRequest.MAX_BYTES) throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Submission body exceeds 256 KiB")
        val tree = runCatching { json.readTree(body) }.getOrNull()
            ?: throw ApiException(ErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON")
        val reply = submissions.accept(principal, id, key, SubmissionRequest.parse(tree, body.size))
        return ResponseEntity.status(reply.status).contentType(MediaType.APPLICATION_JSON).body(reply.body)
    }
}
