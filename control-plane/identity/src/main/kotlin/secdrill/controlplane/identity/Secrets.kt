package secdrill.controlplane.identity

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Opaque bearer secrets. Only the SHA-256 hash is ever persisted or compared. */
object Secrets {
    private val random = SecureRandom()

    fun newToken(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Constant-time comparison of a presented secret against a stored hash. */
    fun matches(raw: String?, storedHash: String): Boolean =
        raw != null && MessageDigest.isEqual(hash(raw).toByteArray(), storedHash.toByteArray())
}
