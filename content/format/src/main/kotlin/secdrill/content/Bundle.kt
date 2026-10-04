package secdrill.content

import secdrill.kernel.Digests
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * A content bundle (08). The public half is what learners may see; the private half is grader-only.
 * File keys are relative paths under `public/` or `private/`.
 */
class ContentBundle(
    val manifest: JsonNode,
    val oracle: JsonNode,
    val publicFiles: Map<String, ByteArray>,
    val privateFiles: Map<String, ByteArray>,
) {
    val scenarioVersionId: String? get() = manifest["scenarioVersionId"]?.takeIf { it.isString }?.asString()

    companion object {
        const val MANIFEST = "manifest.json"
        const val ORACLE = "oracle.json"
        const val SIGNATURE = "signature.json"

        /** Reads `manifest.json`, `oracle.json`, `public/` and `private/` from an authoring directory. */
        fun readDirectory(root: Path, json: JsonMapper = JsonMapper.builder().build()): ContentBundle {
            fun files(prefix: String): Map<String, ByteArray> {
                val dir = root.resolve(prefix)
                if (!Files.isDirectory(dir)) return emptyMap()
                return Files.walk(dir).use { stream ->
                    stream.filter { it.isRegularFile() }.toList()
                        .associate { "$prefix/" + it.relativeTo(dir).toString().replace('\\', '/') to Files.readAllBytes(it) }
                }
            }
            return ContentBundle(
                json.readTree(root.resolve(MANIFEST).toFile()),
                json.readTree(root.resolve(ORACLE).toFile()),
                files("public"),
                files("private"),
            )
        }
    }
}

data class BundleDigests(val contentDigest: String, val oracleDigest: String, val bundleDigest: String)

/**
 * Bundle digests over canonical JSON (RFC 8785) of the documents plus sorted file digests (08, ADR 0003), never over
 * archive bytes, so re-packing does not change the identity of the content.
 */
object BundleDigester {
    const val FORMAT = "secdrill-bundle/1"

    fun digests(bundle: ContentBundle): BundleDigests {
        val content = Digests.canonical(mapOf("manifest" to JsonPlain.of(bundle.manifest), "files" to fileList(bundle.publicFiles)))
        val oracle = Digests.canonical(mapOf("oracle" to JsonPlain.of(bundle.oracle), "files" to fileList(bundle.privateFiles)))
        val whole = Digests.canonical(
            mapOf("format" to FORMAT, "scenarioVersionId" to bundle.scenarioVersionId, "contentDigest" to content, "oracleDigest" to oracle),
        )
        return BundleDigests(content, oracle, whole)
    }

    private fun fileList(files: Map<String, ByteArray>) = files.entries.sortedBy { it.key }
        .map { mapOf("path" to it.key, "sha256" to Digests.sha256Hex(it.value), "byteSize" to it.value.size) }
}

/** Jackson tree to the plain values [secdrill.kernel.CanonicalJson] accepts; non-integers are rejected. */
object JsonPlain {
    fun of(node: JsonNode): Any? = when {
        node.isObject -> node.properties().associate { it.key to of(it.value) }
        node.isArray -> node.values().map(::of)
        node.isString -> node.asString()
        node.isBoolean -> node.asBoolean()
        node.isNull -> null
        node.isIntegralNumber -> node.bigIntegerValue()
        else -> throw IllegalArgumentException("content documents may only hold strings, booleans, null, integers, arrays and objects")
    }
}
