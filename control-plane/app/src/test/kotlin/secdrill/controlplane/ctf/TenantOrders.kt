package secdrill.controlplane.ctf

import secdrill.content.ContentBundle
import secdrill.content.SyntheticBundles
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** The synthetic tenant-orders content (content/labs/tenant-orders) as a test bundle. Synthetic data only. */
object TenantOrders {
    private val json = JsonMapper.builder().build()
    val root: Path = Path.of(System.getProperty("secdrill.contracts.dir")).parent.parent.resolve("content/labs/tenant-orders")
    val privateDir: Path = root.resolve("private")

    /**
     * The Lab image, assembled locally like the Dockerfile but without BuildKit: `docker build` resolves registry
     * metadata for the pinned base and hung inside the test JVM. Here the pinned local base is copied, the app added
     * and the Dockerfile's settings committed. `private/` is never copied. Never pushed.
     */
    val image: String by lazy {
        val name = "secdrill-tenant-orders-build-${java.util.UUID.randomUUID()}"
        val staging = Files.createTempDirectory("tenant-orders")
        try {
            Files.walk(root.resolve("app")).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".py") }.forEach {
                    val target = staging.resolve("app").resolve(root.resolve("app").relativize(it).toString())
                    Files.createDirectories(target.parent)
                    Files.copy(it, target)
                }
            }
            // `docker cp` keeps host permissions: make the tree world-readable like the Dockerfile's COPY.
            Files.walk(staging).use { paths ->
                paths.forEach { Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString(if (Files.isDirectory(it)) "rwxr-xr-x" else "rw-r--r--")) }
            }
            docker("create", "--name", name, CtfFlowTest.PYTHON, "true")
            docker("cp", "$staging/.", "$name:/app")
            docker("commit", "--change", "WORKDIR /app", "--change", "USER 65534:65534", "--change", "ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1",
                "--change", "EXPOSE 8080", "--change", """CMD ["python", "-m", "app.server"]""", name, "secdrill-lab/tenant-orders:test").lines().last { it.isNotBlank() }.trim()
        } finally {
            runCatching { docker("rm", "-f", name) }
            staging.toFile().deleteRecursively()
        }
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(2, java.util.concurrent.TimeUnit.MINUTES) && process.exitValue() == 0) { "docker ${args.first()} failed: $output" }
        return output
    }

    /** Files of a patch fixture (`private/patches/<name>`), keyed by repository path. */
    fun patch(name: String): Map<String, String> {
        val dir = privateDir.resolve("patches/$name")
        return Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".py") }.toList()
                .associate { dir.relativize(it).toString() to Files.readString(it) }
        }
    }

    fun bundle(): ContentBundle {
        val plan = Files.readAllBytes(privateDir.resolve("hidden-tests.json"))
        val tests = json.readTree(plan)["tests"].values().map { it["id"].asString() }
        val expected = mapOf("own-tenant-read" to "allow", "own-tenant-list" to "allow")
        var bundle = SyntheticBundles.withManifest(SyntheticBundles.valid()) {
            it.put("title", "Synthetic tenant order leak")
            it.set("modes", json.valueToTree(listOf("CTF", "PURPLE")))
            (it["runtime"] as ObjectNode).put("imageDigest", image).put("memoryMiB", 256).put("vcpus", 1)
            it.set("patch", json.readTree("""{"language":"Python","allowedPaths":["app/orders.py","app/authz.py"],"maxCompressedBytes":5242880,"maxExpandedBytes":20971520,"maxFiles":100}"""))
        }
        bundle = SyntheticBundles.withOracle(bundle) {
            (it["verifier"] as ObjectNode).set("requires", json.valueToTree(listOf("actorTenant != resourceTenant", "syntheticOrderReturned", "sessionChallengeBound")))
            it.set("hiddenTests", json.valueToTree(tests.map { id -> mapOf("id" to id, "expected" to (expected[id] ?: "deny")) }))
            it.set("mutants", json.valueToTree(listOf("no-change", "deny-everything", "fix-only-direct-route", "trust-client-tenant", "tamper-test-framework")))
        }
        val reference = patch("reference").toSortedMap().entries.joinToString("\n") { "--- ${it.key}\n${it.value}" }.toByteArray()
        return ContentBundle(bundle.manifest, bundle.oracle, bundle.publicFiles,
            mapOf("private/reference.patch" to reference, "private/hidden-tests.json" to plan))
    }
}
