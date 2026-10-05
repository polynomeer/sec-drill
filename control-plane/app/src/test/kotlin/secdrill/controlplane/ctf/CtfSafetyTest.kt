package secdrill.controlplane.ctf

import org.springframework.mock.env.MockEnvironment
import secdrill.controlplane.identity.AuthProperties
import secdrill.controlplane.lab.LabProperties
import secdrill.controlplane.lab.LabSafetyCheck
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Unsafe CTF and isolation settings stop startup (ADR 0008). Unit-level so other prod checks cannot mask them. */
class CtfSafetyTest {
    private val prod = MockEnvironment().apply { setActiveProfiles("prod") }

    @Test
    fun `prod refuses the unverified-isolation override and unverified profiles claiming verification`() {
        val override = LabSafetyCheck(prod, LabProperties(allowUnverifiedIsolation = true), AuthProperties())
        assertTrue("prod cannot run Labs on unverified isolation" in assertFailsWith<IllegalStateException> { override.afterSingletonsInstantiated() }.message!!)
        val claimed = LabSafetyCheck(MockEnvironment(), LabProperties(isolationVerified = true), AuthProperties())
        assertTrue("only the lab-strong profile" in assertFailsWith<IllegalStateException> { claimed.afterSingletonsInstantiated() }.message!!)
    }

    @Test
    fun `prod refuses unverified patch grading and only grading-strong may claim verification`() {
        val override = secdrill.controlplane.submission.GradingSafetyCheck(prod, secdrill.controlplane.submission.GradingProperties(allowUnverifiedIsolation = true))
        assertTrue("prod cannot grade on unverified isolation" in assertFailsWith<IllegalStateException> { override.afterSingletonsInstantiated() }.message!!)
        val claimed = secdrill.controlplane.submission.GradingSafetyCheck(MockEnvironment(), secdrill.controlplane.submission.GradingProperties(isolationVerified = true))
        assertTrue("only the grading-strong profile" in assertFailsWith<IllegalStateException> { claimed.afterSingletonsInstantiated() }.message!!)
    }

    @Test
    fun `prod needs configured flag keys and keys must be long enough`() {
        assertTrue("prod requires secdrill.ctf.keys" in assertFailsWith<IllegalStateException> { CtfSafetyCheck(prod, CtfProperties()).afterSingletonsInstantiated() }.message!!)
        val short = CtfProperties(keys = mapOf("k1" to Base64.getUrlEncoder().encodeToString(ByteArray(16))), activeKey = "k1")
        assertTrue("at least 32 bytes" in assertFailsWith<IllegalStateException> { CtfSafetyCheck(prod, short).afterSingletonsInstantiated() }.message!!)
        val missing = CtfProperties(keys = mapOf("k1" to Base64.getUrlEncoder().encodeToString(ByteArray(32))), activeKey = "k2")
        assertTrue("is not configured" in assertFailsWith<IllegalStateException> { CtfSafetyCheck(MockEnvironment(), missing).afterSingletonsInstantiated() }.message!!)
    }
}
