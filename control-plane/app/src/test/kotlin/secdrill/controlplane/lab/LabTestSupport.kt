package secdrill.controlplane.lab

import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.TEST_ORIGIN
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import secdrill.execution.agent.ExecResult
import secdrill.execution.agent.OwnedRuntime
import secdrill.execution.agent.RuntimeAdapter
import secdrill.execution.protocol.CleanupReceipt
import secdrill.execution.protocol.LabSpec
import secdrill.execution.protocol.ProvisionedLab
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory runtime for lifecycle tests. Not an isolation boundary; isolation is tested with the Docker adapter. */
class FakeRuntime(private val endpoint: String = "http://fake-lab:8080") : RuntimeAdapter {
    override val profile = "fake"
    val running = ConcurrentHashMap<Pair<UUID, Int>, OwnedRuntime>()
    @Volatile var beforeProvisionReturns: (() -> Unit)? = null

    override fun provision(spec: LabSpec): ProvisionedLab {
        running[spec.labId to spec.generation] = OwnedRuntime(spec.labId, spec.generation, "fake-${spec.labId}-${spec.generation}", spec.hardExpiresAt)
        beforeProvisionReturns?.invoke()
        return ProvisionedLab("fake-${spec.labId}-${spec.generation}", endpoint)
    }

    override fun terminate(labId: UUID, generation: Int): CleanupReceipt {
        val removed = running.remove(labId to generation)
        return CleanupReceipt("fake-$labId-$generation", listOfNotNull(removed?.let { "fake:${it.runtimeRef}" }), Instant.now())
    }

    override fun list() = running.values.toList()
    override fun exec(labId: UUID, generation: Int, argv: List<String>) = ExecResult(0, "", false)
}

object LabCalls {
    fun requestLab(browser: TestBrowser, learner: Fixtures.Learner, session: UUID, version: Long = 0, key: String = UUID.randomUUID().toString()): TestResponse =
        browser.send("POST", "/v1/sessions/$session/labs", body = """{"expectedVersion":$version}""", origin = TEST_ORIGIN,
            csrf = learner.login.csrfToken, cookies = learner.cookies, headers = mapOf("Idempotency-Key" to key))

    fun stop(browser: TestBrowser, learner: Fixtures.Learner, session: UUID, version: Long): TestResponse =
        browser.send("POST", "/v1/sessions/$session/stop", body = """{"expectedVersion":$version}""", origin = TEST_ORIGIN,
            csrf = learner.login.csrfToken, cookies = learner.cookies)

    fun connect(browser: TestBrowser, learner: Fixtures.Learner, session: UUID, lab: UUID): TestResponse =
        browser.send("POST", "/v1/sessions/$session/labs/$lab/connect", origin = TEST_ORIGIN, csrf = learner.login.csrfToken, cookies = learner.cookies)
}
