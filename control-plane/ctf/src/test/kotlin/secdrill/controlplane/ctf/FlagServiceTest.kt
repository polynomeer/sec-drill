package secdrill.controlplane.ctf

import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagServiceTest {
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
    private val flags = FlagService(CtfProperties(keys = mapOf("k1" to secret), activeKey = "k1"))
    private val session = UUID.randomUUID()
    private val challenge = UUID.randomUUID()
    private val lab = UUID.randomUUID()

    @Test
    fun `a flag is bound to its Session, challenge and Lab generation`() {
        val nonce = flags.newNonce()
        val flag = flags.issue(session, challenge, nonce, "k1")
        assertTrue(Regex("^SD\\{[A-Za-z0-9_-]{43}}$").matches(flag), flag)
        assertEquals(flag, flags.issue(session, challenge, nonce, "k1"), "deterministic for the same binding")
        val binding = listOf(FlagBinding(lab, 1, nonce, "k1"))
        assertEquals(FlagMatch(lab, 1), flags.match(flag, session, challenge, binding))
        assertNull(flags.match(flag, UUID.randomUUID(), challenge, binding), "another Session's flag")
        assertNull(flags.match(flag, session, UUID.randomUUID(), binding), "another challenge")
        assertNull(flags.match(flag, session, challenge, listOf(FlagBinding(lab, 2, flags.newNonce(), "k1"))), "a replaced Lab generation")
        assertNull(flags.match(flag.dropLast(2) + "A}", session, challenge, binding))
        assertNull(flags.match(flag, session, challenge, listOf(FlagBinding(lab, 1, nonce, "unknown"))), "an unknown key version never matches")
    }

    @Test
    fun `different keys give different flags and receipts verify only unchanged`() {
        val other = FlagService(CtfProperties(keys = mapOf("k1" to Base64.getUrlEncoder().encodeToString(ByteArray(32) { 7 })), activeKey = "k1"))
        val nonce = flags.newNonce()
        assertNotEquals(flags.issue(session, challenge, nonce, "k1"), other.issue(session, challenge, nonce, "k1"))
        val signature = flags.signReceipt("""{"matched":true}""")
        assertTrue(flags.verifyReceipt("""{"matched":true}""", "k1", signature))
        assertFalse(flags.verifyReceipt("""{"matched":false}""", "k1", signature))
        assertFalse(other.verifyReceipt("""{"matched":true}""", "k1", signature))
        assertFalse(flags.verifyReceipt("""{"matched":true}""", "nope", signature))
    }

    @Test
    fun `without configured keys a per-process key is used`() {
        val dev = FlagService(CtfProperties())
        assertEquals("dev-ephemeral", dev.activeKeyVersion)
        val nonce = dev.newNonce()
        assertEquals(dev.issue(session, challenge, nonce, "dev-ephemeral"), dev.issue(session, challenge, nonce, "dev-ephemeral"))
        assertNotEquals(dev.issue(session, challenge, nonce, "dev-ephemeral"), FlagService(CtfProperties()).issue(session, challenge, nonce, "dev-ephemeral"))
    }
}
