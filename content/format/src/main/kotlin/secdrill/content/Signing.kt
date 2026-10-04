package secdrill.content

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Detached signature over a bundle digest (ADR 0006). The key id names a trusted public key on the Control Plane. */
data class BundleSignature(val keyId: String, val algorithm: String, val bundleDigest: String, val signature: String) {
    companion object {
        fun fromJson(node: tools.jackson.databind.JsonNode): BundleSignature {
            fun field(name: String) = requireNotNull(node[name]?.takeIf { it.isString }?.asString()) { "signature.$name is required" }
            return BundleSignature(field("keyId"), field("algorithm"), field("bundleDigest"), field("signature"))
        }
    }
}

/**
 * Ed25519 (JDK built-in). The signed message is domain-separated so a bundle signature cannot be replayed as any
 * other kind of signature. Signing keys stay with authors/CI; the Control Plane only holds public keys.
 */
object BundleSigner {
    const val ALGORITHM = "Ed25519"
    private fun message(digest: String) = "secdrill-bundle-v1\n$digest".toByteArray(Charsets.UTF_8)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class EncodedKeyPair(val privateKey: String, val publicKey: String)

    fun generateKeyPair(): EncodedKeyPair {
        val pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
        return EncodedKeyPair(encoder.encodeToString(pair.private.encoded), encoder.encodeToString(pair.public.encoded))
    }

    fun privateKey(encoded: String): PrivateKey =
        KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(decoder.decode(encoded.trim())))

    fun publicKey(encoded: String): PublicKey =
        KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(decoder.decode(encoded.trim())))

    fun sign(bundleDigest: String, keyId: String, key: PrivateKey): BundleSignature {
        val signer = Signature.getInstance(ALGORITHM).apply { initSign(key); update(message(bundleDigest)) }
        return BundleSignature(keyId, ALGORITHM, bundleDigest, encoder.encodeToString(signer.sign()))
    }

    /** True only for a known key id, the expected digest and a valid Ed25519 signature. */
    fun verify(signature: BundleSignature, expectedDigest: String, trustedKeys: Map<String, PublicKey>): Boolean {
        if (signature.algorithm != ALGORITHM || signature.bundleDigest != expectedDigest) return false
        val key = trustedKeys[signature.keyId] ?: return false
        return runCatching {
            Signature.getInstance(ALGORITHM).apply { initVerify(key); update(message(expectedDigest)) }.verify(decoder.decode(signature.signature))
        }.getOrDefault(false)
    }
}
