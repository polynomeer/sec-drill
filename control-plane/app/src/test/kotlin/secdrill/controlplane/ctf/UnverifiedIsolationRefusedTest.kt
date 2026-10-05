package secdrill.controlplane.ctf

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.lab.LabCalls
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/** Fail closed (17): without the development overrides, no Lab and no patch grading run on unverified isolation. */
@IntegrationTest
@TestPropertySource(properties = ["test.context=unverified-isolation-refused", "secdrill.lab.allow-unverified-isolation=false", "secdrill.grading.allow-unverified-isolation=false"])
class UnverifiedIsolationRefusedTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures

    @Test
    fun `a Lab that needs strong isolation is refused instead of downgraded`() {
        val learner = fixtures.learner()
        val response = LabCalls.requestLab(TestBrowser("http://127.0.0.1:$port"), learner, fixtures.activeSession(learner.userId))
        assertEquals(503, response.status, response.body)
        assertEquals("SERVICE_UNAVAILABLE", JsonMapper.builder().build().readTree(response.body)["code"].asString())
        assertEquals(0, fixtures.count("SELECT count(*) FROM labs WHERE owner_id = ?", learner.userId.value))
    }

    @Test
    fun `a patch is refused when no verified grading runtime exists`() {
        val learner = fixtures.learner()
        val manifest = """{"modes":["PATCH"],"patch":{"language":"Python","allowedPaths":["app/authz.py"],"maxFiles":10}}"""
        val session = fixtures.activeSession(learner.userId, manifest, "PATCH")
        val response = TestBrowser("http://127.0.0.1:$port").send(
            "POST", "/v1/sessions/$session/submissions", origin = secdrill.controlplane.support.TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies,
            headers = mapOf("Idempotency-Key" to java.util.UUID.randomUUID().toString()),
            body = """{"kind":"PATCH","expectedVersion":0,"content":{"files":{"app/authz.py":"x = 1\n"},"explanation":"synthetic"}}""",
        )
        assertEquals(503, response.status, response.body)
        assertEquals(0, fixtures.count("SELECT count(*) FROM submissions WHERE session_id = ?", session))
    }
}
