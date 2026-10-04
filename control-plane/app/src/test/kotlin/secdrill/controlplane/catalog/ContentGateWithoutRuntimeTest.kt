package secdrill.controlplane.catalog

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import secdrill.content.SyntheticBundles
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import secdrill.kernel.OperatorRole
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** With the real configuration (no strong runtime verifier yet) a perfect, signed bundle still cannot be published. */
@IntegrationTest
class ContentGateWithoutRuntimeTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun content(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
        }
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var fixtures: Fixtures

    @Test
    fun `unverified bundles stay drafts`() {
        val browser = TestBrowser("http://127.0.0.1:$port")
        val author = operators.issue(UUID.randomUUID(), OperatorRole.AUTHOR, "author synthetic content", Duration.ofHours(1))
        val reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review synthetic content", Duration.ofHours(1))
        val json = JsonMapper.builder().build()
        val version = UUID.fromString(json.readTree(ContentTestSupport.register(browser, author, SyntheticBundles.valid()).body)["scenarioVersionId"].asString())
        val report = json.readTree(ContentTestSupport.validate(browser, author, version).body)
        assertEquals("INCOMPLETE", report["status"].asString())
        assertEquals("none", report["verifierKind"].asString())
        val runtime = report["checks"].values().filter { it["name"].asString().startsWith("runtime.") }
        assertTrue(runtime.isNotEmpty() && runtime.all { it["result"].asString() == "NOT_RUN" })
        assertTrue(report["checks"].values().filterNot { it["name"].asString().startsWith("runtime.") }.all { it["result"].asString() == "PASS" })
        assertEquals("DRAFT", fixtures.string("SELECT status FROM scenario_versions WHERE id = ?", version))
        assertEquals(409, ContentTestSupport.approve(browser, reviewer, version).status)
    }

    @Test
    fun `prod refuses test verifiers`() {
        val check = ContentSafetyCheck(MockEnvironment().apply { setActiveProfiles("prod") }, ContentProperties(acceptedVerifiers = listOf("test-scripted")))
        assertFailsWith<IllegalStateException> { check.afterSingletonsInstantiated() }
        ContentSafetyCheck(MockEnvironment().apply { setActiveProfiles("prod") }, ContentProperties()).afterSingletonsInstantiated()
    }
}
