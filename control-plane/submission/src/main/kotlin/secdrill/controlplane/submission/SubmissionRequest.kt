package secdrill.controlplane.submission

import secdrill.kernel.ApiException
import secdrill.kernel.Digests
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.FieldError
import secdrill.kernel.SubmissionKind
import secdrill.kernel.Uuids
import tools.jackson.databind.JsonNode
import java.util.UUID

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
    /** FLAG content, held in memory for the HMAC check and then dropped (15). Never stored or logged. */
    val flag: FlagContent?,
    /** PATCH content (OpenAPI PatchSubmission); paths are checked against the pinned manifest by the service. */
    val patch: PatchContent? = null,
    /** DETECTION content: the parsed, limit-checked rule (21) and the learner's explanation. */
    val detection: DetectionContent? = null,
    /** OBJECTIVE/POSTMORTEM content, recorded for human review without an auto-grader (10). */
    val recorded: JsonNode? = null,
) {
    class DetectionContent(val rule: secdrill.simulation.Rule, val ruleJson: JsonNode, val explanation: String)

    class PatchContent(val files: Map<String, String>, val explanation: String) {
        override fun toString() = "PatchContent(files=${files.keys}, explanation=<${explanation.length} chars>)"
    }

    class FlagContent(val challengeId: UUID, val value: String) {
        override fun toString() = "FlagContent(challengeId=$challengeId, value=<redacted>)"
    }

    override fun toString() = "SubmissionRequest(kind=$kind, expectedVersion=$expectedVersion, digest=$digest)"

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
            if (kind == SubmissionKind.PATCH && content != null && content.isObject) errors += patchErrors(content)
            var rule: secdrill.simulation.Rule? = null
            if (kind == SubmissionKind.DETECTION && content != null && content.isObject) {
                (content.propertyNames().toSet() - setOf("rule", "explanation")).forEach { errors += FieldError("content.$it", "unknown field") }
                val explanation = content["explanation"]
                if (explanation == null || !explanation.isString || explanation.asString().length > 8192) errors += FieldError("content.explanation", "must be at most 8192 characters")
                try {
                    rule = secdrill.simulation.RuleParser.parse(content["rule"] ?: throw secdrill.simulation.RuleInvalid(listOf("rule: required")))
                } catch (invalid: secdrill.simulation.RuleInvalid) {
                    invalid.problems.forEach { errors += FieldError("content.rule", it) }
                }
            }
            if (errors.isNotEmpty()) invalid(errors)

            val digest = try {
                Digests.canonical(plain(body))
            } catch (error: IllegalArgumentException) {
                invalid(listOf(FieldError("$", "values must be strings, booleans, null, objects, arrays or safe integers")))
            }
            val flag = if (kind == SubmissionKind.FLAG) FlagContent(Uuids.parse(content!!["challengeId"].asString()), content["flag"].asString()) else null
            val patch = if (kind == SubmissionKind.PATCH) {
                PatchContent(content!!["files"].properties().associate { it.key to it.value.asString() }, content["explanation"].asString())
            } else null
            val detection = if (kind == SubmissionKind.DETECTION) DetectionContent(rule!!, content!!["rule"], content["explanation"].asString()) else null
            val recorded = if (kind == SubmissionKind.OBJECTIVE || kind == SubmissionKind.POSTMORTEM) content else null
            return SubmissionRequest(kind!!, expected!!.asLong(), digest, byteSize, flag, patch, detection, recorded)
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

        private fun patchErrors(content: JsonNode): List<FieldError> = buildList {
            (content.propertyNames().toSet() - setOf("files", "explanation")).forEach { add(FieldError("content.$it", "unknown field")) }
            val files = content["files"]
            if (files == null || !files.isObject || files.size() !in 1..100) {
                add(FieldError("content.files", "must map 1-100 file paths to their full text"))
            } else if (files.properties().any { !it.value.isString || it.value.asString().length > 200_000 || '\u0000' in it.value.asString() }) {
                add(FieldError("content.files", "every file must be text of at most 200000 characters"))
            }
            val explanation = content["explanation"]
            if (explanation == null || !explanation.isString || explanation.asString().length > 8192) add(FieldError("content.explanation", "must be at most 8192 characters"))
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
