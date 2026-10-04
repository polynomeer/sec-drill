package secdrill.controlplane.catalog

import secdrill.content.BundleDigester
import secdrill.content.BundleSigner
import secdrill.content.ContentBundle
import secdrill.controlplane.support.TestBrowser
import secdrill.controlplane.support.TestResponse
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID

/** Test signing key and upload helpers. Keys are generated per JVM; nothing here is a real content key. */
object ContentTestSupport {
    const val KEY_ID = "author-ci-test"
    val keys = BundleSigner.generateKeyPair()
    private val json = JsonMapper.builder().build()

    fun upload(bundle: ContentBundle, signed: Boolean = true, signWith: String = keys.privateKey): String {
        val signature = if (signed) BundleSigner.sign(BundleDigester.digests(bundle).bundleDigest, KEY_ID, BundleSigner.privateKey(signWith)) else null
        val encode = { files: Map<String, ByteArray> -> files.mapValues { Base64.getEncoder().encodeToString(it.value) } }
        return json.writeValueAsString(
            mapOf("manifest" to bundle.manifest, "oracle" to bundle.oracle, "publicFiles" to encode(bundle.publicFiles),
                "privateFiles" to encode(bundle.privateFiles), "signature" to signature),
        )
    }

    fun register(browser: TestBrowser, token: String, bundle: ContentBundle, signed: Boolean = true): TestResponse =
        browser.send("POST", "/ops/v1/content/bundles", body = upload(bundle, signed), bearer = token, cookies = emptyMap())

    fun validate(browser: TestBrowser, token: String, version: UUID) =
        browser.send("POST", "/ops/v1/scenario-versions/$version/validations", bearer = token, cookies = emptyMap())

    fun approve(browser: TestBrowser, token: String, version: UUID) =
        browser.send("POST", "/ops/v1/scenario-versions/$version/approve", bearer = token, cookies = emptyMap())

    fun quarantine(browser: TestBrowser, token: String, version: UUID, reason: String = "isolation risk found") =
        browser.send("POST", "/ops/v1/scenario-versions/$version/quarantine", body = """{"reason":"$reason"}""", bearer = token, cookies = emptyMap())
}
