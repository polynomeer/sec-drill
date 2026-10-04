package secdrill.content

import secdrill.kernel.ValidationStatus
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentValidationTest {
    private val keys = BundleSigner.generateKeyPair()
    private val trusted = mapOf("author-ci" to BundleSigner.publicKey(keys.publicKey))

    private fun sign(bundle: ContentBundle) = BundleSigner.sign(BundleDigester.digests(bundle).bundleDigest, "author-ci", BundleSigner.privateKey(keys.privateKey))
    private fun report(bundle: ContentBundle, verifier: RuntimeVerifier = UnavailableRuntimeVerifier, signature: BundleSignature? = sign(bundle)) =
        ContentValidation.run(bundle, signature, trusted, verifier)
    private fun result(report: ValidationReport, name: String) = report.checks.single { it.name == name }.result

    @Test
    fun `the design examples stay unpublishable because of their placeholders`() {
        val examples = Path.of(System.getProperty("secdrill.contracts.dir")).resolveSibling("examples")
        val json = JsonMapper.builder().build()
        val bundle = ContentBundle(json.readTree(examples.resolve("scenario.json").toFile()), json.readTree(examples.resolve("private-oracle.json").toFile()), emptyMap(), emptyMap())
        val report = report(bundle, signature = null)
        assertEquals(ValidationStatus.FAIL, report.status)
        listOf("manifest.structure", "oracle.structure", "bundle.version-match", "oracle.weights", "separation.public-private")
            .forEach { assertEquals(CheckResult.PASS, result(report, it), it) }
        listOf("gate.image-digest", "gate.oracle-references", "gate.publishable", "signature")
            .forEach { assertEquals(CheckResult.FAIL, result(report, it), it) }
    }

    @Test
    fun `a valid bundle without runtime verification is INCOMPLETE, never PASS`() {
        val report = report(SyntheticBundles.valid())
        assertEquals(ValidationStatus.INCOMPLETE, report.status)
        assertTrue(report.checks.filter { it.name.startsWith("runtime.") }.all { it.result == CheckResult.NOT_RUN })
        assertEquals(ValidationStatus.PASS, report(SyntheticBundles.valid(), SyntheticBundles.ScriptedVerifier()).status)
    }

    @Test
    fun `digest ignores member order and changes with any content`() {
        val bundle = SyntheticBundles.valid()
        val reordered = ContentBundle(JsonMapper.builder().build().readTree(bundle.manifest.toString().let { reorder(it) }), bundle.oracle, bundle.publicFiles, bundle.privateFiles)
        assertEquals(BundleDigester.digests(bundle), BundleDigester.digests(reordered))
        val changedFile = ContentBundle(bundle.manifest, bundle.oracle, mapOf("public/README.md" to "changed".toByteArray()), bundle.privateFiles)
        assertTrue(BundleDigester.digests(changedFile).contentDigest != BundleDigester.digests(bundle).contentDigest)
        val changedOracle = SyntheticBundles.withOracle(bundle) { it.put("referencePatchRef", "private/other.patch") }
        assertTrue(BundleDigester.digests(changedOracle).bundleDigest != BundleDigester.digests(bundle).bundleDigest)
    }

    private fun reorder(json: String): String {
        val node = JsonMapper.builder().build().readTree(json)
        val reversed = node.properties().reversed().associate { it.key to it.value }
        return JsonMapper.builder().build().writeValueAsString(reversed)
    }

    @Test
    fun `tampering after signing breaks the signature`() {
        val bundle = SyntheticBundles.valid()
        val signature = sign(bundle)
        val tampered = SyntheticBundles.withManifest(bundle) { it.put("title", "Tampered") }
        assertEquals(CheckResult.FAIL, result(report(tampered, SyntheticBundles.ScriptedVerifier(), signature), "signature"))
        val untrusted = BundleSigner.sign(BundleDigester.digests(bundle).bundleDigest, "unknown", BundleSigner.privateKey(BundleSigner.generateKeyPair().privateKey))
        assertEquals(CheckResult.FAIL, result(report(bundle, SyntheticBundles.ScriptedVerifier(), untrusted), "signature"))
    }

    @Test
    fun `oracle data in the public half fails separation`() {
        val bundle = SyntheticBundles.valid()
        val leakedTestName = SyntheticBundles.withManifest(bundle) { it.put("brief", "Hint: think about hidden-direct-other-tenant") }
        val leakedKey = SyntheticBundles.withManifest(bundle) { it.putObject("scope").put("solution", "x") }
        val leakedFile = ContentBundle(bundle.manifest, bundle.oracle, bundle.publicFiles + ("public/patch.diff" to bundle.privateFiles.getValue("private/reference.patch")), bundle.privateFiles)
        val leakedSecretRef = ContentBundle(bundle.manifest, bundle.oracle, mapOf("public/env" to "secret-ref:content/synthetic-orders".toByteArray()), bundle.privateFiles)
        listOf(leakedTestName, leakedKey, leakedFile, leakedSecretRef).forEach {
            assertEquals(CheckResult.FAIL, result(report(it), "separation.public-private"))
        }
    }

    @Test
    fun `placeholders, unpublishable flags and unsafe paths block the gate`() {
        val bundle = SyntheticBundles.valid()
        assertEquals(CheckResult.FAIL, result(report(SyntheticBundles.withManifest(bundle) { (it["runtime"] as tools.jackson.databind.node.ObjectNode).put("imageDigest", "PLACEHOLDER") }), "gate.image-digest"))
        assertEquals(CheckResult.FAIL, result(report(SyntheticBundles.withOracle(bundle) { it.put("publishable", false) }), "gate.publishable"))
        assertEquals(CheckResult.FAIL, result(report(SyntheticBundles.withOracle(bundle) { it.put("referencePatchRef", "private/missing.patch") }), "gate.oracle-references"))
        val traversal = ContentBundle(bundle.manifest, bundle.oracle, mapOf("public/../oracle.json" to byteArrayOf(1)), bundle.privateFiles)
        assertEquals(CheckResult.FAIL, result(report(traversal), "bundle.files"))
        assertFalse(report(SyntheticBundles.withOracle(bundle) { it.put("visibility", "PUBLIC") }).checks.all { it.result != CheckResult.FAIL })
    }
}
