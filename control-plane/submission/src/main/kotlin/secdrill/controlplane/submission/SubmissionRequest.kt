package secdrill.controlplane.submission

import secdrill.kernel.ApiException
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.FieldError
import secdrill.kernel.SubmissionKind
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode

/**
 * A validated `SubmissionCreate` body (OpenAPI). `digest` is SHA-256 over the RFC 8785 form of the whole body
 * (D-04), so the same JSON with reordered members is the same request.
 *
 * Per-kind content rules: FLAG is checked fully here. PATCH, DETECTION, OBJECTIVE and POSTMORTEM content is only
 * required to be an object until their graders define it (T07, T08, T10).
 */
class SubmissionRequest private constructor(
    val kind: SubmissionKind,
    val expectedVersion: Long,
    val digest: String,
    val byteSize: Int,
) {
    companion object {
        const val MAX_BYTES = 256 * 1024

        fun parse(body: JsonNode, byteSize: Int): SubmissionRequest {
            if (byteSize > MAX_BYTES) throw ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Submission body exceeds 256 KiB")
            val errors = mutableListOf<FieldError>()
            if (!body.isObject) invalid(listOf(FieldError("$", "must be an object")))
            (body.propertyNames().toSet() - setOf("kind", "expectedVersion", "content")).forEach { errors += FieldError(it, "unknown field") }
            val kind = body["kind"]?.takeIf { it.isString }?.asString()
                ?.let { name -> SubmissionKind.entries.firstOrNull { it.name == name } }
            if (kind == null) errors += FieldError("kind", "must be one of ${SubmissionKind.entries.joinToString()}")
            val expected = body["expectedVersion"]
            if (expected == null || !expected.isIntegralNumber || expected.asLong() < 0) errors += FieldError("expectedVersion", "must be a non-negative integer")
            val content = body["content"]
            if (content == null || !content.isObject) errors += FieldError("content", "must be an object")
            if (kind == SubmissionKind.FLAG && content != null && content.isObject) errors += flagErrors(content)
            if (errors.isNotEmpty()) invalid(errors)

            val digest = try {
                Digests.canonical(plain(body))
            } catch (error: IllegalArgumentException) {
                invalid(listOf(FieldError("$", "values must be strings, booleans, null, objects, arrays or safe integers")))
            }
            return SubmissionRequest(kind!!, expected!!.asLong(), digest, byteSize)
        }

        private fun flagErrors(content: JsonNode): List<FieldError> = buildList {
            (content.propertyNames().toSet() - setOf("challengeId", "flag")).forEach { add(FieldError("content.$it", "unknown field")) }
            val challenge = content["challengeId"]
            if (challenge == null || !challenge.isString || runCatching { Uuids.parse(challenge.asString()) }.isFailure) {
                add(FieldError("content.challengeId", "must be a UUID"))
            }
            val flag = content["flag"]
            if (flag == null || !flag.isString || flag.asString().length !in 1..256) add(FieldError("content.flag", "must be 1-256 characters"))
        }

        private fun invalid(errors: List<FieldError>): Nothing =
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Submission is invalid", ErrorDetails(fieldErrors = errors.take(50)))

        private fun plain(node: JsonNode): Any? = when {
            node.isObject -> node.properties().associate { it.key to plain(it.value) }
            node.isArray -> node.values().map(::plain)
            node.isString -> node.asString()
            node.isBoolean -> node.asBoolean()
            node.isNull -> null
            node.isIntegralNumber -> node.bigIntegerValue()
            node.isNumber -> throw IllegalArgumentException("non-integer number")
            else -> throw IllegalArgumentException("unsupported JSON node")
        }
    }
}
