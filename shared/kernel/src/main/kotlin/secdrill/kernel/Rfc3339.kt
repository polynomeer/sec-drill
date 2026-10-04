package secdrill.kernel

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Contract timestamps are UTC RFC 3339 (00). Input must carry an offset; output is always `Z`. */
object Rfc3339 {
    fun format(instant: Instant): String = DateTimeFormatter.ISO_INSTANT.format(instant)

    fun parse(text: String): Instant =
        try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("not an RFC 3339 timestamp with offset", error)
        }
}
