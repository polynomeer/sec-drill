package secdrill.controlplane.ctf

import secdrill.content.ContentBundle
import secdrill.content.SyntheticBundles
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A synthetic incident Lab under content/labs as a test bundle (T13). Builds the image like the Dockerfile without
 * BuildKit, reads patch fixtures and classifies hidden tests as security (deny) or regression (allow) from the
 * oracle. Synthetic data only; `private/` is never copied into the image.
 */
class LabContent(private val labName: String, private val title: String, private val allowedPaths: List<String>, private val allowIds: Set<String>) {
    private val json = JsonMapper.builder().build()
    val root: Path = Path.of(System.getProperty("secdrill.contracts.dir")).parent.parent.resolve("content/labs/$labName")
    val privateDir: Path = root.resolve("private")

    val image: String by lazy {
        val name = "secdrill-$labName-build-${UUID.randomUUID()}"
        val staging = Files.createTempDirectory(labName)
        try {
            val appDir = root.resolve("app")
            Files.walk(appDir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".py") }.forEach {
                    val target = staging.resolve("app").resolve(appDir.relativize(it).toString())
                    Files.createDirectories(target.parent)
                    Files.copy(it, target)
                }
            }
            Files.walk(staging).use { paths ->
                paths.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString(if (Files.isDirectory(it)) "rwxr-xr-x" else "rw-r--r--")) }
            }
            docker("create", "--name", name, CtfFlowTest.PYTHON, "true")
            docker("cp", "$staging/.", "$name:/app")
            docker("commit", "--change", "WORKDIR /app", "--change", "USER 65534:65534", "--change", "ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1",
                "--change", "EXPOSE 8080", "--change", """CMD ["python", "-m", "app.server"]""", name, "secdrill-lab/$labName:test").lines().last { it.isNotBlank() }.trim()
        } finally {
            runCatching { docker("rm", "-f", name) }
            staging.toFile().deleteRecursively()
        }
    }

    private fun docker(vararg args: String): String {
        val process = ProcessBuilder(listOf("docker") + args).redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null"))).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(2, TimeUnit.MINUTES) && process.exitValue() == 0) { "docker ${args.first()} failed: $output" }
        return output
    }

    /** Patch fixture files keyed by repository path (app/...). */
    fun patch(name: String): Map<String, String> {
        val dir = privateDir.resolve("patches/$name")
        return Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".py") }.toList()
                .associate { "app/" + dir.resolve("app").relativize(it).toString() to Files.readString(it) }
        }
    }

    fun bundle(): ContentBundle {
        val plan = Files.readAllBytes(privateDir.resolve("hidden-tests.json"))
        val tests = json.readTree(plan)["tests"].values().map { it["id"].asString() }
        var bundle = SyntheticBundles.withManifest(SyntheticBundles.valid()) {
            it.put("title", title)
            it.set("modes", json.valueToTree(listOf("CTF", "PURPLE")))
            (it["runtime"] as ObjectNode).put("imageDigest", image).put("memoryMiB", 256).put("vcpus", 1)
            it.set("patch", json.readTree("""{"language":"Python","allowedPaths":${json.writeValueAsString(allowedPaths)},"maxCompressedBytes":5242880,"maxExpandedBytes":20971520,"maxFiles":100}"""))
        }
        bundle = SyntheticBundles.withOracle(bundle) {
            it.set("hiddenTests", json.valueToTree(tests.map { id -> mapOf("id" to id, "expected" to if (id in allowIds) "allow" else "deny") }))
            it.set("mutants", json.valueToTree(Files.list(privateDir.resolve("patches")).use { s -> s.map { p -> p.fileName.toString() }.filter { it != "reference" }.sorted().toList() }))
        }
        val reference = patch("reference").toSortedMap().entries.joinToString("\n") { "--- ${it.key}\n${it.value}" }.toByteArray()
        return ContentBundle(bundle.manifest, bundle.oracle, bundle.publicFiles,
            mapOf("private/hidden-tests.json" to plan, "private/reference.patch" to reference))
    }
}
