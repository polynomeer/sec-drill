package secdrill.controlplane.evidence

import org.springframework.mock.env.MockEnvironment
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The local development store must never serve production (D-15). */
class ArtifactStoreSafetyTest {
    private fun check(vararg profiles: String) =
        ArtifactStoreSafetyCheck(MockEnvironment().apply { setActiveProfiles(*profiles) }, ArtifactProperties()).afterSingletonsInstantiated()

    @Test
    fun `local store is refused under prod and allowed elsewhere`() {
        assertFailsWith<IllegalStateException> { check("prod") }
        check("local")
        check()
    }
}
