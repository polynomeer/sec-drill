package secdrill.controlplane.catalog

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import secdrill.content.BundleSigner
import secdrill.content.RuntimeVerifier
import secdrill.content.UnavailableRuntimeVerifier
import java.security.PublicKey

/**
 * Content publishing settings (ADR 0006).
 * - `trustedKeys`: key id -> Ed25519 public key (base64url X.509). Only public keys live on the Control Plane.
 * - `acceptedVerifiers`: runtime verifier kinds whose results may count toward PASS. The default names the strong
 *   runtime verifier, which does not exist yet, so nothing can be published until it does.
 */
@ConfigurationProperties("secdrill.content")
data class ContentProperties(
    val trustedKeys: Map<String, String> = emptyMap(),
    val acceptedVerifiers: List<String> = listOf(STRONG_RUNTIME),
    val maxUploadBytes: Int = 8 * 1024 * 1024,
) {
    companion object {
        const val STRONG_RUNTIME = "strong-runtime"
    }

    fun publicKeys(): Map<String, PublicKey> = trustedKeys.mapValues { BundleSigner.publicKey(it.value) }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContentProperties::class)
class ContentConfig {
    /** Replaced by the strong-runtime verifier when T06/T08 exist. */
    @Bean
    @ConditionalOnMissingBean
    fun runtimeVerifier(): RuntimeVerifier = UnavailableRuntimeVerifier
}

/** Under `prod` only the strong runtime verifier may count; test doubles must never open the publish gate. */
@Component
class ContentSafetyCheck(private val environment: Environment, private val properties: ContentProperties) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        properties.publicKeys() // fail fast on malformed keys
        check("prod" !in environment.activeProfiles || properties.acceptedVerifiers.all { it == ContentProperties.STRONG_RUNTIME }) {
            "Unsafe content configuration: prod accepts only the ${ContentProperties.STRONG_RUNTIME} verifier"
        }
    }
}
