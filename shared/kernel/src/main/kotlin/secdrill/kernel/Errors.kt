package secdrill.kernel

/** Error codes from the enums.json `errorCodes` catalog; HTTP status and retryability are fixed per code (15). */
enum class ErrorCode(val httpStatus: Int, val retryable: Boolean) {
    MALFORMED_REQUEST(400, false),
    AUTHENTICATION_REQUIRED(401, false),
    FORBIDDEN(403, false),
    NOT_FOUND(404, false),
    VERSION_CONFLICT(409, false),
    IDEMPOTENCY_CONFLICT(409, false),
    INVALID_STATE(409, false),
    MISSING_GATES(409, false),
    NOT_READY(409, true),
    STREAM_CURSOR_EXPIRED(410, false),
    PAYLOAD_TOO_LARGE(413, false),
    VALIDATION_FAILED(422, false),
    UNSUPPORTED_MODE(422, false),
    RATE_LIMITED(429, true),
    QUOTA_EXCEEDED(429, true),
    INTERNAL_ERROR(500, false),
    SERVICE_UNAVAILABLE(503, true),
}

data class FieldError(val field: String, val message: String)

/** Only field errors, latestVersion and missingGates may appear; never stack traces, oracle data or secrets. */
data class ErrorDetails(
    val fieldErrors: List<FieldError>? = null,
    val latestVersion: Long? = null,
    val missingGates: List<String>? = null,
)

/** Wire shape `{code,message,requestId,retryable,details}`. `retryable` always follows the code. */
class ErrorEnvelope private constructor(
    val code: ErrorCode,
    val message: String,
    val requestId: String,
    val details: ErrorDetails?,
) {
    val retryable: Boolean get() = code.retryable

    companion object {
        fun of(code: ErrorCode, message: String, requestId: String, details: ErrorDetails? = null) =
            ErrorEnvelope(code, message, requestId, details)
    }
}

/** A contract error raised by domain code; the HTTP layer renders it as the envelope with [code]'s status. */
class ApiException(
    val code: ErrorCode,
    message: String,
    val details: ErrorDetails? = null,
    /** Sent as `Retry-After` (seconds) for rate limits (09). */
    val retryAfterSeconds: Long? = null,
) : RuntimeException(message)
