package secdrill.controlplane.identity.web

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import secdrill.kernel.ErrorCode
import secdrill.kernel.ErrorEnvelope
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Writes the contract error envelope from servlet filters, which run before MVC exception handling. */
@Component
class ErrorEnvelopeWriter(private val json: JsonMapper) {
    fun write(response: HttpServletResponse, code: ErrorCode, message: String) {
        response.status = code.httpStatus
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        json.writeValue(response.outputStream, ErrorEnvelope.of(code, message, UUID.randomUUID().toString()))
    }

    fun unauthenticated(response: HttpServletResponse) =
        write(response, ErrorCode.AUTHENTICATION_REQUIRED, "Authentication required")

    fun forbidden(response: HttpServletResponse) = write(response, ErrorCode.FORBIDDEN, "Request is not allowed")
}
