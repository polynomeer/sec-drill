package secdrill.controlplane.web

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import secdrill.controlplane.access.ResourceNotFoundException
import secdrill.kernel.ApiException
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorDetails
import secdrill.kernel.ErrorEnvelope
import java.util.UUID

/**
 * Renders every error as the contract envelope (15). Messages are fixed text: exception messages,
 * stack traces and request contents never reach the client.
 */
@RestControllerAdvice
class ApiErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed(error: HttpMessageNotReadableException) =
        respond(ErrorCode.MALFORMED_REQUEST, "Request body is not valid JSON")

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(error: NoResourceFoundException) = respond(ErrorCode.NOT_FOUND, "Resource not found")

    /** Domain contract errors carry their own code; the message is fixed text chosen by the domain code. */
    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException) = respond(error.code, error.message ?: error.code.name, error.details)

    /** Same body as a missing route so other owners' resources stay indistinguishable from absent ones. */
    @ExceptionHandler(ResourceNotFoundException::class)
    fun notOwned(error: ResourceNotFoundException) = respond(ErrorCode.NOT_FOUND, "Resource not found")

    /**
     * Other framework errors (unsupported method or media type, missing parameter) implement ErrorResponse and
     * are client input errors. Anything else is unexpected and logged server-side only.
     */
    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<ErrorEnvelope> {
        if (error is ErrorResponse) {
            when (error.statusCode.value()) {
                404 -> return respond(ErrorCode.NOT_FOUND, "Resource not found")
                413 -> return respond(ErrorCode.PAYLOAD_TOO_LARGE, "Request body is too large")
                in 400..499 -> return respond(ErrorCode.MALFORMED_REQUEST, "Request is not acceptable")
            }
        }
        val response = respond(ErrorCode.INTERNAL_ERROR, "Internal error")
        log.error("Unhandled error requestId={}", response.body!!.requestId, error)
        return response
    }

    private fun respond(code: ErrorCode, message: String, details: ErrorDetails? = null): ResponseEntity<ErrorEnvelope> =
        ResponseEntity.status(code.httpStatus)
            .contentType(MediaType.APPLICATION_JSON)
            .body(ErrorEnvelope.of(code, message, UUID.randomUUID().toString(), details))
}
