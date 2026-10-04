package secdrill.controlplane.catalog

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import secdrill.content.ContentValidation
import secdrill.content.RuntimeVerifier
import secdrill.content.SyntheticBundles
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import secdrill.kernel.OperatorRole
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Content lifecycle with a test-only scripted runtime verifier standing in for the strong runtime (T06/T08), which
 * does not exist. [ContentGateWithoutRuntimeTest] shows that with the real configuration nothing can be published.
 */
@IntegrationTest
@Import(ContentPublishingTest.ScriptedRuntime::class)
@ExtendWith(OutputCaptureExtension::class)
class ContentPublishingTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun content(registry: DynamicPropertyRegistry) {
            registry.add("secdrill.content.trusted-keys.${ContentTestSupport.KEY_ID}") { ContentTestSupport.keys.publicKey }
            registry.add("secdrill.content.accepted-verifiers") { "test-scripted" }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ScriptedRuntime {
        @Bean
        @Primary
        fun scriptedVerifier(): RuntimeVerifier = SyntheticBundles.ScriptedVerifier()
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var db: JdbcConnectionDetails

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private lateinit var author: String
    private lateinit var reviewer: String
    private lateinit var operator: String
    private val authorId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        author = operators.issue(authorId, OperatorRole.AUTHOR, "author synthetic content", Duration.ofHours(1))
        reviewer = operators.issue(UUID.randomUUID(), OperatorRole.REVIEWER, "review synthetic content", Duration.ofHours(1))
        operator = operators.issue(UUID.randomUUID(), OperatorRole.OPERATOR, "incident response", Duration.ofHours(1))
    }

    private fun versionOf(response: secdrill.controlplane.support.TestResponse) = UUID.fromString(json.readTree(response.body)["scenarioVersionId"].asString())
    private fun status(version: UUID) = fixtures.string("SELECT status FROM scenario_versions WHERE id = ?", version)
    private fun learnerGet(path: String) = browser.send("GET", path, cookies = fixtures.learner().cookies)

    private fun sqlState(sql: String, vararg args: Any): String? = DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
        runCatching { c.prepareStatement(sql).use { s -> args.forEachIndexed { i, a -> s.setObject(i + 1, a) }; s.execute() } }
            .exceptionOrNull()?.let { (it as SQLException).sqlState }
    }

    private fun publish(bundle: secdrill.content.ContentBundle): UUID {
        val version = versionOf(ContentTestSupport.register(browser, author, bundle).also { assertEquals(201, it.status, it.body) })
        assertEquals(201, ContentTestSupport.validate(browser, author, version).status)
        assertEquals(204, ContentTestSupport.approve(browser, reviewer, version).status)
        return version
    }

    @Test
    fun `signed and validated content is published by an independent reviewer without exposing the oracle`(output: CapturedOutput) {
        val scenario = UUID.randomUUID()
        val bundle = SyntheticBundles.valid(scenarioId = scenario)
        val registered = ContentTestSupport.register(browser, author, bundle)
        assertEquals(201, registered.status, registered.body)
        val version = versionOf(registered)
        assertEquals("DRAFT", status(version))
        assertEquals(404, learnerGet("/v1/scenarios/$scenario").status, "drafts are invisible to learners")

        val report = ContentTestSupport.validate(browser, author, version)
        assertEquals(201, report.status, report.body)
        assertEquals("PASS", json.readTree(report.body)["status"].asString(), report.body)
        assertEquals("VALIDATED", status(version))
        assertEquals(404, learnerGet("/v1/scenarios/$scenario").status, "validated is not published")

        assertEquals(204, ContentTestSupport.approve(browser, reviewer, version).status)
        assertEquals("PUBLISHED", status(version))
        val detail = learnerGet("/v1/scenarios/$scenario")
        assertEquals(200, detail.status, detail.body)
        val view = json.readTree(detail.body)
        assertEquals(version.toString(), view["scenarioVersionId"].asString())
        val schema = json.readTree(Path.of(System.getProperty("secdrill.contracts.dir"), "openapi.yaml").toFile())["components"]["schemas"]["ScenarioDetail"]
        assertEquals(schema["properties"].propertyNames().toSet(), view.propertyNames().toSet())
        assertEquals(1, fixtures.count("SELECT count(*) FROM challenges WHERE version_id = ?", version))

        val secrets = ContentValidation.sensitiveValues(bundle.oracle) + "--- synthetic reference patch ---"
        listOf(detail.body to "public API", report.body to "validation report", output.all to "logs").forEach { (text, where) ->
            secrets.forEach { assertFalse(it in text, "oracle value reached the $where") }
        }
        assertFalse(fixtures.string("SELECT public_manifest::text FROM scenario_versions WHERE id = ?", version)!!.contains("hidden-direct-other-tenant"))
    }

    @Test
    fun `unsigned or tampered bundles fail validation and cannot be approved`() {
        val unsigned = versionOf(ContentTestSupport.register(browser, author, SyntheticBundles.valid(), signed = false))
        assertEquals("FAIL", json.readTree(ContentTestSupport.validate(browser, author, unsigned).body)["status"].asString())
        assertEquals("DRAFT", status(unsigned))
        assertEquals(409, ContentTestSupport.approve(browser, reviewer, unsigned).status)

        val bundle = SyntheticBundles.valid()
        val signedForOriginal = json.readTree(ContentTestSupport.upload(bundle))
        val tampered = SyntheticBundles.withManifest(bundle) { it.put("title", "Tampered after signing") }
        val body = json.readTree(ContentTestSupport.upload(tampered, signed = false)) as tools.jackson.databind.node.ObjectNode
        body.set("signature", signedForOriginal["signature"])
        val registered = browser.send("POST", "/ops/v1/content/bundles", body = body.toString(), bearer = author, cookies = emptyMap())
        val report = json.readTree(ContentTestSupport.validate(browser, author, versionOf(registered)).body)
        assertEquals("FAIL", report["status"].asString())
        assertTrue(report["checks"].values().any { it["name"].asString() == "signature" && it["result"].asString() == "FAIL" })
    }

    @Test
    fun `authors cannot approve their own content, in the API or in the database`() {
        val version = versionOf(ContentTestSupport.register(browser, author, SyntheticBundles.valid()))
        ContentTestSupport.validate(browser, author, version)
        val sameIdentityReviewer = operators.issue(authorId, OperatorRole.REVIEWER, "self review attempt", Duration.ofHours(1))
        assertEquals(403, ContentTestSupport.approve(browser, sameIdentityReviewer, version).status)
        val report = fixtures.string("SELECT id::text FROM content_validation_reports WHERE scenario_version_id = ? AND status = 'PASS'", version)!!
        assertEquals("23514", sqlState("INSERT INTO content_approvals VALUES (?, ?, ?, ?::uuid, now())", version, authorId, authorId, report))
        assertEquals("P0001", sqlState("UPDATE scenario_versions SET status = 'PUBLISHED', published_at = now() WHERE id = ?", version),
            "publishing without an approval is refused by the database")
    }

    @Test
    fun `roles are separated and learners cannot reach content operations`() {
        assertEquals(403, ContentTestSupport.register(browser, reviewer, SyntheticBundles.valid()).status)
        val version = versionOf(ContentTestSupport.register(browser, author, SyntheticBundles.valid()))
        ContentTestSupport.validate(browser, author, version)
        assertEquals(403, ContentTestSupport.approve(browser, author, version).status)
        assertEquals(403, ContentTestSupport.approve(browser, operator, version).status)
        val learner = fixtures.learner()
        assertEquals(401, browser.send("POST", "/ops/v1/scenario-versions/$version/approve", cookies = learner.cookies).status)
    }

    @Test
    fun `published content is pinned, versions move forward and quarantine hides them`() {
        val scenario = UUID.randomUUID()
        val v1 = publish(SyntheticBundles.valid(scenarioId = scenario, version = 1))
        assertEquals("P0001", sqlState("UPDATE scenario_versions SET public_manifest = '{}' WHERE id = ?", v1), "published content is immutable")
        assertEquals("P0001", sqlState("UPDATE scenario_versions SET status = 'DRAFT' WHERE id = ?", v1), "status only moves forward")
        assertEquals(409, ContentTestSupport.register(browser, author, SyntheticBundles.valid(scenarioId = scenario, versionId = v1)).status)
        val otherScenarioSameFamily = SyntheticBundles.withManifest(SyntheticBundles.valid()) { it.put("family", "synthetic-${scenario.toString().take(8)}") }
        assertEquals(409, ContentTestSupport.register(browser, author, otherScenarioSameFamily).status, "a family belongs to one scenario")

        val v2 = publish(SyntheticBundles.valid(scenarioId = scenario, version = 2))
        assertEquals(v2.toString(), json.readTree(learnerGet("/v1/scenarios/$scenario").body)["scenarioVersionId"].asString())
        assertEquals(v1.toString(), json.readTree(learnerGet("/v1/scenarios/$scenario?versionId=$v1").body)["scenarioVersionId"].asString(),
            "an older published version stays addressable for sessions pinned to it")

        assertEquals(204, ContentTestSupport.quarantine(browser, operator, v2).status)
        assertEquals("QUARANTINED", status(v2))
        assertEquals(v1.toString(), json.readTree(learnerGet("/v1/scenarios/$scenario").body)["scenarioVersionId"].asString())
        assertEquals(404, learnerGet("/v1/scenarios/$scenario?versionId=$v2").status)
        assertEquals(409, ContentTestSupport.quarantine(browser, operator, v2).status)
        assertEquals("P0001", sqlState("UPDATE scenario_versions SET status = 'PUBLISHED' WHERE id = ?", v2), "quarantine is final for a version")
    }

    @Test
    fun `the design examples cannot be published`() {
        val examples = Path.of(System.getProperty("secdrill.contracts.dir")).resolveSibling("examples")
        val bundle = secdrill.content.ContentBundle(json.readTree(examples.resolve("scenario.json").toFile()), json.readTree(examples.resolve("private-oracle.json").toFile()), emptyMap(), emptyMap())
        val registered = ContentTestSupport.register(browser, author, bundle)
        assertEquals(201, registered.status, registered.body)
        val version = versionOf(registered)
        try {
            val report = json.readTree(ContentTestSupport.validate(browser, author, version).body)
            assertEquals("FAIL", report["status"].asString())
            assertEquals(409, ContentTestSupport.approve(browser, reviewer, version).status)
        } finally {
            // The example ids are fixed; quarantine keeps this test repeatable within one database.
            ContentTestSupport.quarantine(browser, operator, version)
        }
    }
}
