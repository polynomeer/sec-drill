package secdrill.execution.agent

import secdrill.execution.protocol.CleanupReceipt
import secdrill.execution.protocol.LabSpec
import secdrill.execution.protocol.ProvisionedLab
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `local-trusted` profile (17): hardened Docker containers for development and CI only. NOT strong isolation —
 * containers share the host kernel and this agent drives the Docker daemon. External pilots must use the
 * `lab-strong` microVM runtime (ADR-002, D-10), which this host cannot run.
 *
 * Hardening applied to every Lab: its own `--internal` network (no route out, no host gateway), read-only root,
 * all capabilities dropped, no-new-privileges, the local-trusted seccomp denylist (verified active before the Lab is
 * handed out; Docker Desktop runs containers unconfined by default), non-root user, memory/CPU/PID limits, no host
 * mounts or sockets, no published ports. Resources carry labId, generation, runner id, hard expiry and an HMAC ownership signature;
 * nothing is deleted on name alone.
 */
class LocalTrustedDockerAdapter(
    private val runnerId: String,
    private val ownershipKey: ByteArray,
    private val image: String,
    private val clock: Clock = Clock.systemUTC(),
    private val docker: String = "docker",
) : RuntimeAdapter {
    override val profile = "local-trusted"

    companion object {
        const val OUTPUT_LIMIT = 1024 * 1024
        private const val L = "secdrill"
    }

    private val json = JsonMapper.builder().build()

    /** The Docker CLI reads the profile from a local path, so it is materialized once per adapter. */
    private val seccompProfile: String by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/secdrill/agent/local-trusted-seccomp.json")) { "seccomp profile missing" }.use { it.readBytes() }
        java.nio.file.Files.createTempFile("secdrill-seccomp-", ".json").also { it.toFile().deleteOnExit(); java.nio.file.Files.write(it, bytes) }.toString()
    }

    private fun name(labId: UUID, generation: Int) = "lab-$labId-$generation"

    private fun signature(labId: UUID, generation: Int, expiresAt: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ownershipKey, "HmacSHA256")) }
        return mac.doFinal("$labId|$generation|$runnerId|$expiresAt".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun labels(labId: UUID, generation: Int, expiresAt: Long) = listOf(
        "$L.lab" to labId.toString(), "$L.generation" to generation.toString(), "$L.runner" to runnerId,
        "$L.hard-expires-at" to expiresAt.toString(), "$L.owner-sig" to signature(labId, generation, expiresAt),
    ).flatMap { (k, v) -> listOf("--label", "$k=$v") }

    override fun provision(spec: LabSpec): ProvisionedLab {
        val name = name(spec.labId, spec.generation)
        val expires = spec.hardExpiresAt.epochSecond
        run(listOf("network", "create", "--internal") + labels(spec.labId, spec.generation, expires) + name)
        run(
            listOf(
                "run", "-d", "--name", name, "--hostname", "app", "--network", name, "--network-alias", "app",
                "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--security-opt", "seccomp=$seccompProfile", "--user", "65534:65534",
                "--pids-limit", spec.pids.toString(), "--memory", "${spec.memoryMiB}m", "--memory-swap", "${spec.memoryMiB}m",
                "--cpus", spec.vcpus.toString(), "--restart", "no",
            ) + labels(spec.labId, spec.generation, expires) + listOf(
                image, "sh", "-c", "mkdir -p /tmp/www && echo synthetic-lab > /tmp/www/index.html && exec httpd -f -p 8080 -h /tmp/www",
            ),
        )
        // Fail closed: a Lab whose processes are not under the seccomp filter is never handed out.
        val mode = run(listOf("exec", name, "grep", "^Seccomp:", "/proc/1/status"), check = false).output.substringAfter("Seccomp:").trim()
        if (mode != "2") {
            terminate(spec.labId, spec.generation)
            error("seccomp filter is not active for $name (mode '$mode')")
        }
        return ProvisionedLab(runtimeRef = name, endpoint = "http://$name:8080")
    }

    override fun terminate(labId: UUID, generation: Int): CleanupReceipt {
        val name = name(labId, generation)
        val removed = mutableListOf<String>()
        // Order (17): stop processes, delete network, then the container's writable layer goes with it.
        if (owned("container", name)) {
            run(listOf("rm", "-f", "-v", name)); removed += "container:$name"
        }
        if (owned("network", name)) {
            run(listOf("network", "rm", name)); removed += "network:$name"
        }
        return CleanupReceipt(name, removed, clock.instant())
    }

    override fun list(): List<OwnedRuntime> {
        val ids = run(listOf("ps", "-a", "-q", "--filter", "label=$L.runner=$runnerId")).output.lines().filter { it.isNotBlank() }
        return ids.mapNotNull { id -> inspectLabels("container", id)?.let(::ownedFrom) }
    }

    override fun exec(labId: UUID, generation: Int, argv: List<String>): ExecResult =
        run(listOf("exec", name(labId, generation)) + argv, check = false, timeoutSeconds = 60)

    /** Raw inspect of a runtime's Docker settings, for isolation verification. */
    fun inspect(labId: UUID, generation: Int): String = run(listOf("inspect", name(labId, generation))).output

    private fun owned(kind: String, name: String): Boolean = inspectLabels(kind, name)?.let(::ownedFrom) != null

    private fun inspectLabels(kind: String, ref: String): Map<String, String>? {
        val result = run(listOf(kind, "inspect", "--format", if (kind == "network") "{{json .Labels}}" else "{{json .Config.Labels}}", ref), check = false)
        if (result.exitCode != 0) return null
        return json.readTree(result.output.trim()).properties().associate { it.key to it.value.asString() }
    }

    private fun ownedFrom(labels: Map<String, String>): OwnedRuntime? {
        val labId = labels["$L.lab"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val generation = labels["$L.generation"]?.toIntOrNull() ?: return null
        val expires = labels["$L.hard-expires-at"]?.toLongOrNull() ?: return null
        if (labels["$L.runner"] != runnerId || labels["$L.owner-sig"] != signature(labId, generation, expires)) return null
        return OwnedRuntime(labId, generation, name(labId, generation), Instant.ofEpochSecond(expires))
    }

    /** argv only, never a shell string; output is drained concurrently and capped at [OUTPUT_LIMIT]. */
    private fun run(args: List<String>, check: Boolean = true, timeoutSeconds: Long = 120): ExecResult {
        val process = ProcessBuilder(listOf(docker) + args).redirectErrorStream(true).start()
        val capture = BoundedCapture(process.inputStream).also { it.start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("docker ${args.firstOrNull()} timed out")
        }
        capture.join()
        val result = ExecResult(process.exitValue(), capture.text(), capture.truncated)
        check(!check || result.exitCode == 0) { "docker ${args.take(2).joinToString(" ")} failed with exit ${result.exitCode}" }
        return result
    }

    private class BoundedCapture(private val input: InputStream) : Thread() {
        private val buffer = java.io.ByteArrayOutputStream()
        @Volatile var truncated = false
        override fun run() {
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                val room = OUTPUT_LIMIT - buffer.size()
                if (room > 0) buffer.write(chunk, 0, minOf(room, read))
                if (read > room) truncated = true
            }
        }
        fun text() = buffer.toString(Charsets.UTF_8)
    }
}
