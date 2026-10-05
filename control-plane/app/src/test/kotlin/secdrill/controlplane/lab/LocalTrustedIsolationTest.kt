package secdrill.controlplane.lab

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.TestPropertySource
import secdrill.controlplane.identity.WorkloadCredentialService
import secdrill.controlplane.support.Fixtures
import secdrill.controlplane.support.IntegrationTest
import secdrill.controlplane.support.TestBrowser
import secdrill.execution.agent.HttpLabControl
import secdrill.execution.agent.LocalTrustedDockerAdapter
import secdrill.execution.agent.RunnerAgent
import secdrill.execution.protocol.LabControl
import secdrill.execution.protocol.LabSpec
import secdrill.execution.protocol.ProvisionDecision
import secdrill.kernel.WorkloadKind
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Isolation checks for the `local-trusted` Docker profile (17; prompt 07 "실제 검증"). Results describe this
 * development profile only: containers share the host kernel, so none of this is strong isolation evidence. The
 * `lab-strong` microVM runtime cannot run on this host (no KVM) and remains unverified (D-10).
 */
@IntegrationTest
@TestPropertySource(properties = ["test.context=local-trusted-isolation"])
class LocalTrustedIsolationTest {
    companion object {
        const val IMAGE = "busybox@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e"
        const val RUNNER = "runner-local-trusted"
    }

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var fixtures: Fixtures
    @Autowired private lateinit var workloads: WorkloadCredentialService

    private val adapter = LocalTrustedDockerAdapter(RUNNER, "test-ownership-key-not-a-secret".toByteArray(), IMAGE)
    private val json = JsonMapper.builder().build()

    private fun spec(pids: Int = 256, memoryMiB: Int = 256, expires: Instant = Instant.now().plusSeconds(600)) =
        LabSpec(UUID.randomUUID(), 1, 1, memoryMiB, 512, pids, expires, listOf("app.lab.internal"))

    private fun docker(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("docker") + args).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        return process.waitFor() to output
    }

    private fun exists(kind: String, name: String) = docker(kind, "inspect", name).first == 0

    @AfterTest
    fun cleanUp() {
        docker("ps", "-aq", "--filter", "label=secdrill.runner=$RUNNER").second.lines().filter { it.isNotBlank() }.forEach { docker("rm", "-f", it) }
        docker("network", "ls", "-q", "--filter", "label=secdrill.runner=$RUNNER").second.lines().filter { it.isNotBlank() }.forEach { docker("network", "rm", it) }
    }

    private fun sh(spec: LabSpec, script: String) = adapter.exec(spec.labId, spec.generation, listOf("sh", "-c", script))

    @Test
    fun `hardening flags are applied and there is no host mount, socket or published port`() {
        val lab = spec()
        adapter.provision(lab)
        val host = json.readTree(adapter.inspect(lab.labId, lab.generation))[0]["HostConfig"]
        assertTrue(host["ReadonlyRootfs"].asBoolean())
        assertFalse(host["Privileged"].asBoolean())
        assertEquals(listOf("ALL"), host["CapDrop"].values().map { it.asString() })
        assertTrue(host["SecurityOpt"].values().any { it.asString() == "no-new-privileges" })
        assertEquals(256, host["PidsLimit"].asInt())
        assertEquals(256L * 1024 * 1024, host["Memory"].asLong())
        assertTrue(host["Binds"] == null || host["Binds"].isNull || host["Binds"].isEmpty)
        assertTrue(host["PortBindings"] == null || host["PortBindings"].isNull || host["PortBindings"].isEmpty)
        assertTrue(host["Devices"] == null || host["Devices"].isNull || host["Devices"].isEmpty)

        assertEquals("65534", sh(lab, "id -u").output.trim())
        assertNotEquals(0, sh(lab, "test -e /var/run/docker.sock").exitCode, "no Docker socket")
        assertEquals("0", sh(lab, "grep -c -E 'docker.sock|/host_mnt|/Users' /proc/mounts || true").output.trim(), "no host paths mounted")
        assertNotEquals(0, sh(lab, "touch /root-write").exitCode, "read-only root")
        assertTrue(sh(lab, "grep CapEff /proc/self/status").output.contains("0000000000000000"), "no effective capabilities")
        assertEquals("2", sh(lab, "grep Seccomp: /proc/self/status").output.substringAfter("Seccomp:").trim(), "seccomp filter active")
        listOf("unshare -U true", "unshare -n true", "unshare -m true").forEach { assertNotEquals(0, sh(lab, it).exitCode, "$it must be refused") }
        val networks = docker("network", "inspect", "--format", "{{.Internal}}", "lab-${lab.labId}-1").second.trim()
        assertEquals("true", networks, "the Lab network has no route out")
    }

    @Test
    fun `egress, metadata, Control Plane, other Labs and DNS are unreachable while the Lab app works`() {
        val a = spec()
        val b = spec()
        adapter.provision(a)
        adapter.provision(b)
        Thread.sleep(500)
        assertEquals("synthetic-lab", sh(a, "wget -q -T 3 -O- http://app:8080/").output.trim(), "positive control: the Lab's own app answers")
        val bIp = docker("inspect", "--format", "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", "lab-${b.labId}-1").second.trim()
        val blocked = mapOf(
            "external IPv4" to "wget -q -T 3 -O- http://1.1.1.1/",
            "external IPv6" to "wget -q -T 3 -O- 'http://[2606:4700:4700::1111]/'",
            "external DNS" to "nslookup example.com",
            "cloud metadata" to "wget -q -T 3 -O- http://169.254.169.254/latest/meta-data/",
            "Control Plane via host" to "wget -q -T 3 -O- http://host.docker.internal:$port/actuator/health",
            "Control Plane via host gateway IP" to "wget -q -T 3 -O- http://192.168.65.254:$port/actuator/health",
            "other Lab by name" to "wget -q -T 3 -O- http://lab-${b.labId}-1:8080/",
            "other Lab by IP" to "wget -q -T 3 -O- http://$bIp:8080/",
        )
        blocked.forEach { (what, probe) -> assertNotEquals(0, sh(a, probe).exitCode, "$what must be unreachable") }
    }

    @Test
    fun `PID limit holds and exec output is capped at 1 MiB`() {
        val lab = spec(pids = 64)
        adapter.provision(lab)
        val forks = sh(lab, "n=0; while [ \$n -lt 120 ]; do sleep 3 & n=\$((n+1)); done; wait")
        assertTrue("can't fork" in forks.output, "the 65th process must be refused")
        assertEquals(64, json.readTree(adapter.inspect(lab.labId, lab.generation))[0]["HostConfig"]["PidsLimit"].asInt())
        Thread.sleep(3500) // let the background sleeps exit so the next probe can fork
        val flood = sh(lab, "yes 0123456789 | head -c 3000000")
        assertTrue(flood.truncated)
        assertEquals(LocalTrustedDockerAdapter.OUTPUT_LIMIT, flood.output.toByteArray().size)
    }

    @Test
    fun `a memory flood is killed at the cgroup limit`() {
        val lab = spec(memoryMiB = 64)
        adapter.provision(lab)
        val flood = sh(lab, "awk 'BEGIN { s = \"x\"; while (1) s = s s }'")
        assertEquals(137, flood.exitCode, "the flooding process must be OOM-killed: ${flood.output}")
        assertEquals(64L * 1024 * 1024, json.readTree(adapter.inspect(lab.labId, lab.generation))[0]["HostConfig"]["Memory"].asLong())
        assertEquals("synthetic-lab", sh(lab, "wget -q -T 3 -O- http://app:8080/").output.trim(), "the Lab survives the killed process")
    }

    @Test
    fun `hard TTL is enforced locally while Control is unreachable`() {
        val lab = spec(expires = Instant.now().plusSeconds(2))
        adapter.provision(lab)
        val unreachable = object : LabControl by unreachableControl() {}
        Thread.sleep(2500)
        val receipts = RunnerAgent(unreachable, adapter).enforceLocalTtl()
        assertEquals(1, receipts.size)
        assertFalse(exists("container", "lab-${lab.labId}-1"))
        assertFalse(exists("network", "lab-${lab.labId}-1"))
    }

    @Test
    fun `a runtime created after cancellation is reclaimed and forged labels are never deleted`() {
        val control = HttpLabControl("http://127.0.0.1:$port", workloads.issue(RUNNER, WorkloadKind.AGENT, Duration.ofHours(1)))
        val learner = fixtures.learner()
        val session = fixtures.activeSession(learner.userId)
        val browser = TestBrowser("http://127.0.0.1:$port")
        assertEquals(202, LabCalls.requestLab(browser, learner, session).status)
        val assignment = assertNotNull(control.claim())
        control.start(assignment)
        assertEquals(202, LabCalls.stop(browser, learner, session, fixtures.count("SELECT version FROM sessions WHERE id = ?", session).toLong()).status)
        val created = adapter.provision(assignment.lab)
        assertEquals(ProvisionDecision.TERMINATE, control.provisioned(assignment, created))
        assertTrue(exists("container", created.runtimeRef))
        val agent = RunnerAgent(control, adapter)
        generateSequence { agent.runOnce() }.first { it.lab.labId == assignment.lab.labId }
        assertFalse(exists("container", created.runtimeRef), "late-created runtime must be reclaimed")
        assertFalse(exists("network", created.runtimeRef))
        val receipt = fixtures.string("SELECT cleanup_receipt::text FROM labs WHERE id = ?", assignment.lab.labId)!!
        assertTrue("container:${created.runtimeRef}" in receipt && "network:${created.runtimeRef}" in receipt, receipt)

        val forged = "lab-${UUID.randomUUID()}-1"
        docker("run", "-d", "--name", forged, "--label", "secdrill.runner=$RUNNER", "--label", "secdrill.lab=${UUID.randomUUID()}",
            "--label", "secdrill.generation=1", "--label", "secdrill.hard-expires-at=0", "--label", "secdrill.owner-sig=forged", IMAGE, "sleep", "60")
        assertTrue(adapter.list().none { it.runtimeRef == forged }, "unsigned runtimes are not ours")
        agent.reconcile()
        agent.enforceLocalTtl()
        assertTrue(exists("container", forged), "a runtime without a valid ownership signature is never deleted")
    }

    private fun unreachableControl(): LabControl = object : LabControl {
        private fun down(): Nothing = error("control plane unreachable")
        override fun claim() = down()
        override fun start(assignment: secdrill.execution.protocol.LabAssignment) = down()
        override fun heartbeat(assignment: secdrill.execution.protocol.LabAssignment) = down()
        override fun provisioned(assignment: secdrill.execution.protocol.LabAssignment, lab: secdrill.execution.protocol.ProvisionedLab) = down()
        override fun provisionFailed(assignment: secdrill.execution.protocol.LabAssignment, resourcesMayExist: Boolean) = down()
        override fun terminated(assignment: secdrill.execution.protocol.LabAssignment, receipt: secdrill.execution.protocol.CleanupReceipt) = down()
        override fun cleanupFailed(assignment: secdrill.execution.protocol.LabAssignment) = down()
        override fun reconcile(observed: List<secdrill.execution.protocol.ObservedLab>) = down()
    }
}
