package secdrill.execution.agent

import secdrill.execution.protocol.CleanupReceipt
import secdrill.execution.protocol.LabSpec
import secdrill.execution.protocol.PatchObservation
import secdrill.execution.protocol.PatchRunOutcome
import secdrill.execution.protocol.PatchTask
import secdrill.execution.protocol.ProvisionedLab
import secdrill.kernel.Digests
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
 * mounts or sockets, no published ports. Resources carry labId, generation, runner id, hard expiry and an HMAC
 * ownership signature; nothing is deleted on name alone.
 *
 * With `relayImage` set (local development, T07) each Lab also gets an ingress relay: Docker Desktop cannot route
 * from the host into an `--internal` network, so a hardened relay container joins the Lab network and a per-Lab
 * bridge, publishes one loopback port and forwards only to `app:8080`. The relay is platform code and has egress
 * on that bridge; the Lab container itself still has none. Without a relay the endpoint is the in-network address.
 */
class LocalTrustedDockerAdapter(
    private val runnerId: String,
    private val ownershipKey: ByteArray,
    /** Image for platform test Labs whose spec names no content image; runs a fixed synthetic page. */
    private val defaultImage: String?,
    private val clock: Clock = Clock.systemUTC(),
    private val docker: String = "docker",
    private val relayImage: String? = null,
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
    private fun relayName(labId: UUID, generation: Int) = "${name(labId, generation)}-relay"
    private fun ingressName(labId: UUID, generation: Int) = "${name(labId, generation)}-in"

    private val relayScript: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/secdrill/agent/ingress-relay.py")) { "relay script missing" }.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun signature(labId: UUID, generation: Int, expiresAt: Long): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ownershipKey, "HmacSHA256")) }
        return mac.doFinal("$labId|$generation|$runnerId|$expiresAt".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun labels(labId: UUID, generation: Int, expiresAt: Long, role: String) = listOf(
        "$L.lab" to labId.toString(), "$L.generation" to generation.toString(), "$L.runner" to runnerId,
        "$L.hard-expires-at" to expiresAt.toString(), "$L.owner-sig" to signature(labId, generation, expiresAt), "$L.role" to role,
    ).flatMap { (k, v) -> listOf("--label", "$k=$v") }

    private fun hardening(pids: Int, memoryMiB: Int, vcpus: Int) = listOf(
        "--read-only", "--tmpfs", "/tmp:rw,nosuid,nodev,size=64m",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--security-opt", "seccomp=$seccompProfile", "--user", "65534:65534",
        "--pids-limit", pids.toString(), "--memory", "${memoryMiB}m", "--memory-swap", "${memoryMiB}m",
        "--cpus", vcpus.toString(), "--restart", "no",
    )

    override fun provision(spec: LabSpec): ProvisionedLab {
        val name = name(spec.labId, spec.generation)
        val expires = spec.hardExpiresAt.epochSecond
        val contentImage = spec.image
        require(contentImage == null || Regex("^sha256:[a-f0-9]{64}$").matches(contentImage)) { "content image must be a sha256 digest" }
        val image = contentImage ?: checkNotNull(defaultImage) { "no image for this Lab" }
        // Content images run their own command; platform test Labs serve a fixed synthetic page.
        val command = if (contentImage != null) emptyList() else
            listOf("sh", "-c", "mkdir -p /tmp/www && echo synthetic-lab > /tmp/www/index.html && exec httpd -f -p 8080 -h /tmp/www")
        // Flags reach the container through the docker CLI's environment (`-e NAME`), never through argv.
        val envNames = spec.targetEnv.keys.onEach { require(Regex("^SECDRILL_FLAG_[A-Z0-9_]{1,64}$").matches(it)) { "unexpected target variable" } }
        run(listOf("network", "create", "--internal") + labels(spec.labId, spec.generation, expires, "network") + name)
        run(
            listOf("run", "-d", "--name", name, "--hostname", "app", "--network", name, "--network-alias", "app") +
                hardening(spec.pids, spec.memoryMiB, spec.vcpus) + envNames.flatMap { listOf("-e", it) } +
                labels(spec.labId, spec.generation, expires, "app") + image + command,
            environment = spec.targetEnv,
        )
        requireSeccomp(spec, name)
        val relayImage = relayImage ?: return ProvisionedLab(runtimeRef = name, endpoint = "http://$name:8080")

        val ingress = ingressName(spec.labId, spec.generation)
        val relay = relayName(spec.labId, spec.generation)
        run(listOf("network", "create") + labels(spec.labId, spec.generation, expires, "ingress") + ingress)
        run(
            listOf("create", "--name", relay, "--network", ingress, "-p", "127.0.0.1::8080") + hardening(64, 64, 1) +
                labels(spec.labId, spec.generation, expires, "relay") + listOf(relayImage, "python", "-c", relayScript),
        )
        run(listOf("network", "connect", name, relay))
        run(listOf("start", relay))
        requireSeccomp(spec, relay)
        val port = run(listOf("port", relay, "8080/tcp")).output.lines().first { it.startsWith("127.0.0.1:") }.substringAfterLast(":").trim()
        return ProvisionedLab(runtimeRef = name, endpoint = "http://127.0.0.1:$port")
    }

    /** Fail closed: a Lab whose processes are not under the seccomp filter is never handed out. */
    private fun requireSeccomp(spec: LabSpec, container: String) {
        val mode = run(listOf("exec", container, "grep", "^Seccomp:", "/proc/1/status"), check = false).output.substringAfter("Seccomp:").trim()
        if (mode != "2") {
            terminate(spec.labId, spec.generation)
            error("seccomp filter is not active for $container (mode '$mode')")
        }
    }

    override fun terminate(labId: UUID, generation: Int): CleanupReceipt {
        val name = name(labId, generation)
        val removed = mutableListOf<String>()
        // Order (17): cut ingress, stop processes, delete networks; writable layers go with the containers.
        listOf(relayName(labId, generation), name).forEach { container ->
            if (owned("container", container, labId, generation)) {
                run(listOf("rm", "-f", "-v", container)); removed += "container:$container"
            }
        }
        listOf(ingressName(labId, generation), name).forEach { network ->
            if (owned("network", network, labId, generation)) {
                run(listOf("network", "rm", network)); removed += "network:$network"
            }
        }
        return CleanupReceipt(name, removed, clock.instant())
    }

    override fun list(): List<OwnedRuntime> {
        val ids = run(listOf("ps", "-a", "-q", "--filter", "label=$L.runner=$runnerId")).output.lines().filter { it.isNotBlank() }
        return ids.mapNotNull { id -> inspectLabels("container", id)?.let(::ownedFrom) }.distinctBy { it.labId to it.generation }
    }

    override fun exec(labId: UUID, generation: Int, argv: List<String>): ExecResult =
        run(listOf("exec", name(labId, generation)) + argv, check = false, timeoutSeconds = 60)

    private val driverScript: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/secdrill/agent/grading-driver.py")) { "grading driver missing" }.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Patch grading on the local-trusted profile (20, T08, ADR 0009). NOT the grading-strong microVM.
     *
     * A fresh environment per job attempt, never the learner's Lab: an `--internal` network with no route out, a
     * volume holding only the learner's allowed files (written by a platform helper with no network), a compile step
     * in its own no-network container, the patched app, and a separate supervisor container that sends the hidden
     * tests and judges responses. The patched app never sees the test plan and cannot write the supervisor's output.
     * Everything is removed afterwards, whatever happened.
     */
    fun gradePatch(jobId: UUID, attempt: Int, task: PatchTask): PatchObservation {
        val base = "grade-$jobId-$attempt"
        val expires = clock.instant().epochSecond + task.timeoutSeconds + 300
        val tag = labels(jobId, attempt, expires, "grading")
        val pathPattern = Regex("^[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}$")
        require(task.files.keys.all { pathPattern.matches(it) && ".." !in it.split("/") }) { "patch paths must be validated before grading" }
        require(Regex("^sha256:[a-f0-9]{64}$").matches(task.image)) { "grading image must be a sha256 digest" }
        val helper = checkNotNull(defaultImage) { "no helper image for grading" }
        val driverImage = checkNotNull(relayImage) { "no supervisor image for grading" }
        return try {
            run(listOf("network", "create", "--internal") + tag + base)
            run(listOf("volume", "create") + tag + base)
            // The helper runs as root without capabilities so it can write the root-owned volume; the app reads it read-only.
            task.files.forEach { (path, content) ->
                run(
                    listOf("run", "--rm", "-i", "--network", "none", "-v", "$base:/w") + hardening(32, 64, 1).filterNot { it == "65534:65534" || it == "--user" } +
                        listOf("--user", "0:0") + tag + listOf(helper, "sh", "-c", "mkdir -p \"$(dirname \"$1\")\" && cat > \"$1\"", "sh", "/w/$path"),
                    input = content.toByteArray(Charsets.UTF_8),
                )
            }
            val prepare = "cp -r /app/. /tmp/w/ && cp -r /patch/. /tmp/w/ && cd /tmp/w"
            val compiled = run(
                listOf("run", "--rm", "--network", "none", "-v", "$base:/patch:ro") + hardening(task.pids, task.memoryMiB, 1) + tag +
                    // Same hardening as the app container: fail closed (exit 99) unless the seccomp filter is active.
                    listOf(task.image, "sh", "-c", "grep -q '^Seccomp:[[:space:]]*2' /proc/self/status || exit 99; { $prepare; } || exit 98; python -m compileall -q app >/dev/null 2>&1 || exit 3"),
                check = false, timeoutSeconds = 60,
            )
            // Only exit 3 is the learner's compile error; Docker (125-127) and setup failures are platform errors.
            when (compiled.exitCode) {
                0 -> Unit
                3 -> return PatchObservation(PatchRunOutcome.COMPILE_FAILED, false, emptyMap(), null)
                else -> return PatchObservation(PatchRunOutcome.PLATFORM_ERROR, false, emptyMap(), null)
            }
            run(
                listOf("run", "-d", "--name", "$base-app", "--hostname", "app", "--network", base, "--network-alias", "app", "-v", "$base:/patch:ro") +
                    hardening(task.pids, task.memoryMiB, 1) + tag + listOf(task.image, "sh", "-c", "$prepare && exec python -m app.server"),
            )
            val plan = json.readTree(task.testPlan) as tools.jackson.databind.node.ObjectNode
            plan.put("readyTimeoutSeconds", 20)
            val supervised = run(
                listOf("run", "--rm", "-i", "--network", base) + hardening(64, 128, 1) + tag + listOf(driverImage, "python", "-c", driverScript),
                check = false, timeoutSeconds = task.timeoutSeconds.toLong(), input = json.writeValueAsBytes(plan),
            )
            val report = runCatching { json.readTree(supervised.output.trim().lines().last()) }.getOrNull()
            if (supervised.exitCode != 0 || supervised.truncated || report?.get("results")?.isObject != true) {
                return PatchObservation(PatchRunOutcome.PLATFORM_ERROR, false, emptyMap(), null)
            }
            val results = report["results"].properties().associate { it.key to it.value.asBoolean() }
            PatchObservation(PatchRunOutcome.COMPLETED, report["ready"]?.asBoolean() == true, results, Digests.sha256Hex(supervised.output.toByteArray()))
        } catch (error: Exception) {
            PatchObservation(PatchRunOutcome.PLATFORM_ERROR, false, emptyMap(), null)
        } finally {
            run(listOf("rm", "-f", "-v", "$base-app"), check = false)
            run(listOf("network", "rm", base), check = false)
            run(listOf("volume", "rm", "-f", base), check = false)
        }
    }

    /** Grading resources this runner owns whose hard expiry passed (a crashed run); removed by label, after the signature check. */
    fun reclaimExpiredGrading(): List<String> {
        val removed = mutableListOf<String>()
        listOf("container" to listOf("ps", "-aq"), "network" to listOf("network", "ls", "-q"), "volume" to listOf("volume", "ls", "-q")).forEach { (kind, lister) ->
            run(lister + listOf("--filter", "label=$L.runner=$runnerId", "--filter", "label=$L.role=grading"), check = false).output.lines().filter { it.isNotBlank() }.forEach { ref ->
                val format = if (kind == "container") "{{json .Config.Labels}}" else "{{json .Labels}}"
                val labels = run(listOf(kind, "inspect", "--format", format, ref), check = false).takeIf { it.exitCode == 0 }
                    ?.let { json.readTree(it.output.trim()).properties().associate { p -> p.key to p.value.asString() } } ?: return@forEach
                val owned = signedLabels(labels) ?: return@forEach
                if (owned.hardExpiresAt.isAfter(clock.instant())) return@forEach
                val remove = when (kind) { "container" -> listOf("rm", "-f", "-v", ref); "network" -> listOf("network", "rm", ref); else -> listOf("volume", "rm", "-f", ref) }
                if (run(remove, check = false).exitCode == 0) removed += "$kind:$ref"
            }
        }
        return removed
    }

    /** Raw inspect of a runtime's Docker settings, for isolation verification. */
    fun inspect(labId: UUID, generation: Int): String = run(listOf("inspect", name(labId, generation))).output

    private fun owned(kind: String, name: String, labId: UUID, generation: Int): Boolean =
        inspectLabels(kind, name)?.let(::ownedFrom)?.let { it.labId == labId && it.generation == generation } == true

    private fun inspectLabels(kind: String, ref: String): Map<String, String>? {
        val result = run(listOf(kind, "inspect", "--format", if (kind == "network") "{{json .Labels}}" else "{{json .Config.Labels}}", ref), check = false)
        if (result.exitCode != 0) return null
        return json.readTree(result.output.trim()).properties().associate { it.key to it.value.asString() }
    }

    /** Lab runtimes only; grading resources are reclaimed separately ([reclaimExpiredGrading]). */
    private fun ownedFrom(labels: Map<String, String>): OwnedRuntime? = if (labels["$L.role"] == "grading") null else signedLabels(labels)

    private fun signedLabels(labels: Map<String, String>): OwnedRuntime? {
        val labId = labels["$L.lab"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        val generation = labels["$L.generation"]?.toIntOrNull() ?: return null
        val expires = labels["$L.hard-expires-at"]?.toLongOrNull() ?: return null
        if (labels["$L.runner"] != runnerId || labels["$L.owner-sig"] != signature(labId, generation, expires)) return null
        return OwnedRuntime(labId, generation, name(labId, generation), Instant.ofEpochSecond(expires))
    }

    /** argv only, never a shell string; output is drained concurrently and capped at [OUTPUT_LIMIT]. */
    private fun run(
        args: List<String>, check: Boolean = true, timeoutSeconds: Long = 120, environment: Map<String, String> = emptyMap(), input: ByteArray? = null,
    ): ExecResult {
        val process = ProcessBuilder(listOf(docker) + args).redirectErrorStream(true).also { it.environment().putAll(environment) }
            .also { if (input == null) it.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))) }.start()
        if (input != null) process.outputStream.use { it.write(input) }
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
