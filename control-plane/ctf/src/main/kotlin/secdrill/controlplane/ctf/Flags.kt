package secdrill.controlplane.ctf

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Flag keys (09). `keys` maps a key version to a base64url secret of at least 32 bytes; `activeKey` names the version
 * new Labs use. Older versions stay listed so flags of running Labs still verify after a rotation. Without keys
 * (local development and tests) one random key per process is used; `prod` refuses to start without keys.
 */
@ConfigurationProperties("secdrill.ctf")
data class CtfProperties(
    val keys: Map<String, String> = emptyMap(),
    val activeKey: String? = null,
    /** Wrong flags allowed per Session per minute before 429 (09). */
    val wrongFlagsPerMinute: Int = 10,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CtfProperties::class)
class CtfConfig

@Component
class CtfSafetyCheck(private val environment: Environment, private val properties: CtfProperties) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        properties.keys.forEach { (version, secret) ->
            check(FlagService.VERSION.matches(version)) { "Unsafe CTF configuration: flag key version '$version' is malformed" }
            check(runCatching { Base64.getUrlDecoder().decode(secret).size >= 32 }.getOrDefault(false)) {
                "Unsafe CTF configuration: flag key '$version' must be base64url of at least 32 bytes"
            }
        }
        properties.activeKey?.let { check(it in properties.keys) { "Unsafe CTF configuration: active flag key '$it' is not configured" } }
        if ("prod" in environment.activeProfiles) {
            check(properties.activeKey != null) { "Unsafe CTF configuration: prod requires secdrill.ctf.keys and secdrill.ctf.active-key" }
        }
    }
}

/** What a FLAG submission matched. Contains no flag text. */
data class FlagMatch(val labId: UUID, val generation: Int)

/** A Lab generation's flag binding as stored: its nonce and key version, never the flag. */
data class FlagBinding(val labId: UUID, val generation: Int, val nonce: ByteArray, val keyVersion: String)

/**
 * Session flags (09): `SD{base64url(HMAC-SHA256(secret[keyVersion], len‖sessionId ‖ len‖challengeId ‖ len‖nonce))}`.
 * Each Lab generation has its own nonce, so a flag from another Session or from a replaced Lab never matches.
 * Comparison is constant time. Flags exist only in memory here and in the Lab's target data.
 */
@Service
class FlagService(private val properties: CtfProperties) {
    companion object {
        val VERSION = Regex("^[a-z0-9-]{1,40}$")
        private const val EPHEMERAL = "dev-ephemeral"
        private const val RECEIPT_DOMAIN = "secdrill-flag-receipt-v1"
    }

    private val random = SecureRandom()
    private val ephemeral: ByteArray by lazy { ByteArray(32).also(random::nextBytes) }

    val activeKeyVersion: String get() = properties.activeKey ?: EPHEMERAL

    private fun secret(version: String): ByteArray? = when {
        version in properties.keys -> Base64.getUrlDecoder().decode(properties.keys.getValue(version))
        version == EPHEMERAL && properties.activeKey == null -> ephemeral
        else -> null
    }

    fun newNonce(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun issue(sessionId: UUID, challengeId: UUID, nonce: ByteArray, keyVersion: String): String {
        val key = checkNotNull(secret(keyVersion)) { "flag key version is not available" }
        return "SD{" + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, uuid(sessionId), uuid(challengeId), nonce)) + "}"
    }

    /** The binding whose flag equals [candidate], checking every candidate in constant time per binding. */
    fun match(candidate: String, sessionId: UUID, challengeId: UUID, bindings: List<FlagBinding>): FlagMatch? {
        val presented = candidate.toByteArray(Charsets.UTF_8)
        var found: FlagMatch? = null
        for (binding in bindings) {
            val expected = secret(binding.keyVersion)?.let { issue(sessionId, challengeId, binding.nonce, binding.keyVersion) } ?: continue
            if (MessageDigest.isEqual(presented, expected.toByteArray(Charsets.UTF_8)) && found == null) found = FlagMatch(binding.labId, binding.generation)
        }
        return found
    }

    /** HMAC over a canonical receipt body, keyed separately from flags (domain-separated from the active key). */
    fun signReceipt(canonicalBody: String, keyVersion: String = activeKeyVersion): String {
        val key = mac(checkNotNull(secret(keyVersion)) { "receipt key version is not available" }, RECEIPT_DOMAIN.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, canonicalBody.toByteArray(Charsets.UTF_8)))
    }

    fun verifyReceipt(canonicalBody: String, keyVersion: String, signature: String): Boolean {
        if (secret(keyVersion) == null) return false
        return MessageDigest.isEqual(signReceipt(canonicalBody, keyVersion).toByteArray(), signature.toByteArray())
    }

    private fun uuid(id: UUID): ByteArray = ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

    /** Length-prefixed parts so no two different inputs share an encoding. */
    private fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        parts.forEach { hmac.update(ByteBuffer.allocate(4).putInt(it.size).array()); hmac.update(it) }
        return hmac.doFinal()
    }
}
