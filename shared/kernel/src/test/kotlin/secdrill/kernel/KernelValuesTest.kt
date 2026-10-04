package secdrill.kernel

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KernelValuesTest {
    @Test
    fun `canonical UUIDs parse`() {
        val text = "20000000-0000-4000-8000-000000000001"
        assertEquals(UUID.fromString(text), Uuids.parse(text))
    }

    @Test
    fun `non-canonical UUID text is rejected even though UUID fromString accepts it`() {
        UUID.fromString("1-1-1-1-1")
        listOf("1-1-1-1-1", "20000000000040008000000000000001", " 20000000-0000-4000-8000-000000000001", "")
            .forEach { assertFailsWith<IllegalArgumentException>(it) { Uuids.parse(it) } }
    }

    @Test
    fun `timestamps format as UTC with Z`() {
        assertEquals("2026-10-04T00:00:00Z", Rfc3339.format(Instant.parse("2026-10-04T00:00:00Z")))
        assertEquals("2026-10-04T00:00:00.123Z", Rfc3339.format(Instant.parse("2026-10-04T00:00:00.123Z")))
    }

    @Test
    fun `offsets normalize to the same instant`() {
        assertEquals(Instant.parse("2026-10-03T15:00:00Z"), Rfc3339.parse("2026-10-04T00:00:00+09:00"))
    }

    @Test
    fun `timestamps without offset are rejected`() {
        listOf("2026-10-04T00:00:00", "2026-10-04 00:00:00Z", "2026-10-04")
            .forEach { assertFailsWith<IllegalArgumentException>(it) { Rfc3339.parse(it) } }
    }

    @Test
    fun `retryable always follows the error code`() {
        assertEquals(true, ErrorEnvelope.of(ErrorCode.SERVICE_UNAVAILABLE, "unavailable", "r1").retryable)
        assertEquals(false, ErrorEnvelope.of(ErrorCode.VERSION_CONFLICT, "conflict", "r2", ErrorDetails(latestVersion = 4)).retryable)
    }
}
