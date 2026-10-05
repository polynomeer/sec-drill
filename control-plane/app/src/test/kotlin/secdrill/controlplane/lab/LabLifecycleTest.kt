package secdrill.controlplane.lab

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.identity.OperatorAccessService
import secdrill.controlplane.identity.WorkloadCredentialService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.MutableClock
import secdrill.controlplane.support.TestBrowser
import secdrill.execution.agent.HttpLabControl
import secdrill.execution.agent.RunnerAgent
import secdrill.execution.protocol.Ack
import secdrill.execution.protocol.LabAction
import secdrill.execution.protocol.ProvisionDecision
import secdrill.execution.protocol.ProvisionedLab
import secdrill.kernel.OperatorRole
import secdrill.kernel.WorkloadKind
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lab lifecycle over the real internal API with an in-memory runtime (13, 16, 17; prompt 07). Isolation itself is
 * covered by [LocalTrustedIsolationTest]; nothing here claims strong isolation.
 */
@IntegrationTest
@TestPropertySource(properties = ["test.context=lab-lifecycle"])
class LabLifecycleTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var workloads: WorkloadCredentialService
    @Autowired private lateinit var operators: OperatorAccessService
    @Autowired private lateinit var labJobs: LabJobService
    @Autowired private lateinit var clock: MutableClock
    @Autowired private lateinit var jdbc: JdbcClient

    private val json = JsonMapper.builder().build()
    private lateinit var browser: TestBrowser
    private lateinit var runtime: FakeRuntime
    private lateinit var control: HttpLabControl
    private lateinit var agent: RunnerAgent

    @BeforeTest
    fun setUp() {
        browser = TestBrowser("http://127.0.0.1:$port")
        runtime = FakeRuntime()
        control = HttpLabControl("http://127.0.0.1:$port", workloads.issue("runner-a", WorkloadKind.AGENT, Duration.ofHours(1)))
        agent = RunnerAgent(control, runtime)
        // Each test owns the claimable work: close Labs left by earlier tests in this shared context.
        jdbc.sql("UPDATE jobs SET state = 'CANCELLED', worker_id = NULL, lease_until = NULL WHERE kind IN ('PROVISION','CLEANUP') AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')").update()
        jdbc.sql(
            """UPDATE labs SET state = 'TERMINATED', desired_state = 'TERMINATED', terminate_reason = coalesce(terminate_reason, 'OPERATOR'),
               terminate_requested_at = coalesce(terminate_requested_at, now()), cleanup_confirmed_at = now(), cleanup_receipt = '{}' WHERE cleanup_confirmed_at IS NULL""",
        ).update()
    }

    private data class Started(val learner: Fixtures.Learner, val session: UUID, val lab: UUID)

    private fun requested(): Started {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val response = LabCalls.requestLab(browser, learner, session)
        assertEquals(202, response.status, response.body)
        return Started(learner, session, UUID.fromString(json.readTree(response.body)["id"].asString()))
    }

    private fun labState(lab: UUID) = fixtures.string("SELECT state FROM labs WHERE id = ?", lab)
    private fun sessionVersion(session: UUID) = fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()

    @Test
    fun `request, provision, stop and reclaim with a receipt`() {
        val (learner, session, lab) = requested()
        assertEquals("REQUESTED", labState(lab))
        assertNotNull(agent.runOnce())
        assertEquals("READY", labState(lab))
        assertEquals(1, runtime.running.size)

        val stopped = LabCalls.stop(browser, learner, session, sessionVersion(session))
        assertEquals(202, stopped.status, stopped.body)
        assertEquals("CANCELLED", json.readTree(stopped.body)["status"].asString())
        assertEquals("TERMINATING", labState(lab))
        assertEquals(LabAction.CLEANUP, agent.runOnce()!!.action)
        assertEquals("TERMINATED", labState(lab))
        assertTrue(runtime.running.isEmpty())
        assertTrue(fixtures.string("SELECT cleanup_receipt::text FROM labs WHERE id = ?", lab)!!.contains("fake:"))
        assertEquals(1, fixtures.count("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'LabTerminated'", lab))

        // Quota is free again for this user once cleanup is confirmed.
        val next = fixtures.activeSession(learner.userId)
        assertEquals(202, LabCalls.requestLab(browser, learner, next).status)
    }

    @Test
    fun `duplicate requests make one Lab and the user quota is one active Lab`() {
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val key = UUID.randomUUID().toString()
        val first = LabCalls.requestLab(browser, learner, session, key = key)
        assertEquals(first.body, LabCalls.requestLab(browser, learner, session, key = key).body)
        assertEquals(409, LabCalls.requestLab(browser, learner, session, version = 1).status, "one Lab per Session")
        val other = fixtures.activeSession(learner.userId)
        val quota = LabCalls.requestLab(browser, learner, other)
        assertEquals(429, quota.status)
        assertEquals("QUOTA_EXCEEDED", json.readTree(quota.body)["code"].asString())
        assertEquals(1, fixtures.count("SELECT count(*) FROM labs WHERE owner_id = ?", learner.userId.value))
    }

    @Test
    fun `cancel before any runner claims it, so no runtime is ever created`() {
        val (learner, session, lab) = requested()
        assertEquals(202, LabCalls.stop(browser, learner, session, sessionVersion(session)).status)
        assertEquals("TERMINATED", labState(lab))
        assertTrue(fixtures.string("SELECT cleanup_receipt::text FROM labs WHERE id = ?", lab)!!.contains("never-created"))
        assertNull(agent.runOnce())
        assertTrue(runtime.running.isEmpty())
    }

    @Test
    fun `cancel during provisioning makes the late callback reclaim the runtime instead of going READY`() {
        val (learner, session, lab) = requested()
        runtime.beforeProvisionReturns = { assertEquals(202, LabCalls.stop(browser, learner, session, sessionVersion(session)).status) }
        assertEquals(LabAction.PROVISION, agent.runOnce()!!.action)
        runtime.beforeProvisionReturns = null
        assertEquals("TERMINATING", labState(lab), "a cancelled Lab must never become READY")
        assertEquals(1, runtime.running.size, "the runtime that was created exists until cleanup")
        assertEquals(LabAction.CLEANUP, agent.runOnce()!!.action)
        assertEquals("TERMINATED", labState(lab))
        assertTrue(runtime.running.isEmpty())
        assertTrue(fixtures.count("SELECT count(*) FROM audit_events WHERE purpose = 'lab late callback' AND action LIKE ?", "%$lab%") == 1)
    }

    @Test
    fun `reports from another runner or an expired lease are stale`() {
        val (_, _, lab) = requested()
        val assignment = assertNotNull(control.claim())
        assertEquals(Ack.ACCEPTED, control.start(assignment))
        val intruder = HttpLabControl("http://127.0.0.1:$port", workloads.issue("runner-b", WorkloadKind.AGENT, Duration.ofHours(1)))
        assertEquals(ProvisionDecision.STALE, intruder.provisioned(assignment, ProvisionedLab("forged", "http://evil:80")))
        clock.advance(Duration.ofSeconds(31))
        assertEquals(ProvisionDecision.STALE, control.provisioned(assignment, ProvisionedLab("late", "http://fake-lab:8080")))
        assertEquals("PROVISIONING", labState(lab))
        assertEquals(null, fixtures.string("SELECT runtime_ref FROM labs WHERE id = ?", lab))
    }

    @Test
    fun `idle TTL and hard TTL terminate Labs and activity extends idle only`() {
        val (_, _, idle) = requested()
        agent.runOnce()
        clock.advance(Duration.ofMinutes(10))
        labJobs.touch(idle)
        clock.advance(Duration.ofMinutes(10))
        labJobs.sweepOnce()
        assertEquals("READY", labState(idle), "activity 10 minutes ago keeps the 15-minute idle window open")
        clock.advance(Duration.ofMinutes(6))
        labJobs.sweepOnce()
        assertEquals("IDLE_TTL", fixtures.string("SELECT terminate_reason FROM labs WHERE id = ?", idle))
        agent.runOnce()
        assertEquals("TERMINATED", labState(idle))

        val (_, _, hard) = requested()
        agent.runOnce()
        repeat(7) { clock.advance(Duration.ofMinutes(9)); labJobs.touch(hard); labJobs.sweepOnce() }
        assertEquals("HARD_TTL", fixtures.string("SELECT terminate_reason FROM labs WHERE id = ?", hard), "activity never extends the hard TTL")
    }

    @Test
    fun `reconciliation removes orphans and closes Labs whose runtime is gone`() {
        val (_, _, kept) = requested()
        agent.runOnce()
        val orphan = UUID.randomUUID()
        runtime.running[orphan to 1] = secdrill.execution.agent.OwnedRuntime(orphan, 1, "fake-$orphan-1", java.time.Instant.now().plusSeconds(600))
        val removed = agent.reconcile()
        assertEquals(listOf("fake-$orphan-1"), removed.map { it.runtimeRef })
        assertEquals("READY", labState(kept))

        runtime.running.clear()
        agent.reconcile()
        assertEquals("TERMINATED", labState(kept))
        assertEquals("RUNTIME_LOST", fixtures.string("SELECT terminate_reason FROM labs WHERE id = ?", kept))
    }

    @Test
    fun `failed provisioning frees the quota or schedules cleanup`() {
        val (_, _, clean) = requested()
        val first = assertNotNull(control.claim())
        control.start(first)
        assertEquals(Ack.ACCEPTED, control.provisionFailed(first, resourcesMayExist = false))
        assertEquals("TERMINATED", labState(clean))

        val (_, _, dirty) = requested()
        val second = assertNotNull(control.claim())
        control.start(second)
        control.provisionFailed(second, resourcesMayExist = true)
        assertEquals("FAILED", labState(dirty))
        assertEquals(LabAction.CLEANUP, agent.runOnce()!!.action)
        assertEquals("TERMINATED", labState(dirty))
    }

    @Test
    fun `failed cleanup keeps the quota, is retried after a delay and raises one overdue alert`() {
        val (learner, session, lab) = requested()
        agent.runOnce()
        val failing = object : secdrill.execution.agent.RuntimeAdapter by runtime {
            @Volatile var fail = true
            override fun terminate(labId: UUID, generation: Int) =
                if (fail) error("synthetic runtime delete failure") else runtime.terminate(labId, generation)
        }
        val flaky = RunnerAgent(control, failing)
        assertEquals(202, LabCalls.stop(browser, learner, session, sessionVersion(session)).status)
        assertEquals(LabAction.CLEANUP, flaky.runOnce()!!.action)
        assertEquals("CLEANUP_FAILED", labState(lab))
        assertEquals(1, runtime.running.size, "the runtime is still there")
        val other = fixtures.activeSession(learner.userId)
        assertEquals(429, LabCalls.requestLab(browser, learner, other).status, "an unconfirmed cleanup still holds the quota")

        assertNull(flaky.runOnce(), "no retry before the delay")
        clock.advance(Duration.ofSeconds(31))
        labJobs.sweepOnce()
        assertEquals(LabAction.CLEANUP, flaky.runOnce()!!.action)
        assertEquals("CLEANUP_FAILED", labState(lab))

        val overdue = "SELECT count(*) FROM audit_events WHERE purpose = 'lab cleanup overdue' AND action LIKE ?"
        clock.advance(Duration.ofMinutes(5))
        labJobs.sweepOnce()
        labJobs.sweepOnce()
        assertEquals(1, fixtures.count(overdue, "%$lab%"), "one operator alert, not one per sweep")

        failing.fail = false
        assertEquals(LabAction.CLEANUP, flaky.runOnce()!!.action)
        assertEquals("TERMINATED", labState(lab))
        assertTrue(runtime.running.isEmpty())
        assertEquals(202, LabCalls.requestLab(browser, learner, other).status, "quota released after confirmed cleanup")
    }

    @Test
    fun `operators can stop a Lab and connect needs a READY Lab of the owner`() {
        val (learner, session, lab) = requested()
        assertEquals(409, LabCalls.connect(browser, learner, session, lab).status, "not ready yet")
        agent.runOnce()
        assertEquals(200, LabCalls.connect(browser, learner, session, lab).status)
        assertEquals(404, LabCalls.connect(browser, fixtures.learner(), session, lab).status)
        val operator = operators.issue(UUID.randomUUID(), OperatorRole.OPERATOR, "incident stop", Duration.ofHours(1))
        assertEquals(204, browser.send("POST", "/ops/v1/labs/$lab/stop", bearer = operator, cookies = emptyMap()).status)
        assertEquals("OPERATOR", fixtures.string("SELECT terminate_reason FROM labs WHERE id = ?", lab))
    }

    @Test
    fun `workload credentials are scoped to the internal API and their kind`() {
        val agentToken = workloads.issue("runner-c", WorkloadKind.AGENT, Duration.ofHours(1))
        val gatewayToken = workloads.issue("gateway-a", WorkloadKind.GATEWAY, Duration.ofHours(1))
        val learner = fixtures.learner()
        val operator = operators.issue(UUID.randomUUID(), OperatorRole.OPERATOR, "probe", Duration.ofHours(1))
        assertEquals(401, browser.send("POST", "/internal/v1/lab-jobs/claim", cookies = learner.cookies).status)
        assertEquals(401, browser.send("POST", "/internal/v1/lab-jobs/claim", bearer = operator, cookies = emptyMap()).status)
        assertEquals(403, browser.send("POST", "/internal/v1/lab-jobs/claim", bearer = gatewayToken, cookies = emptyMap()).status)
        assertEquals(403, browser.send("GET", "/internal/v1/gateway/labs/${UUID.randomUUID()}", bearer = agentToken, cookies = emptyMap()).status)
        assertEquals(401, browser.send("GET", "/v1/auth/session", bearer = agentToken, cookies = emptyMap()).status)
        assertEquals(401, browser.send("GET", "/ops/v1/whoami", bearer = agentToken, cookies = emptyMap()).status)
        workloads.revokeRunner("runner-c")
        assertEquals(401, browser.send("POST", "/internal/v1/lab-jobs/claim", bearer = agentToken, cookies = emptyMap()).status)
    }
}
