package secdrill.controlplane.submission

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Patch grading runtime (17, 20, ADR 0009). Grading needs the `grading-strong` profile, separate from Labs. Without a
 * verified grading runtime, PATCH submissions are refused (503) unless development explicitly allows unverified
 * isolation; every such result is a demo result.
 */
@ConfigurationProperties("secdrill.grading")
data class GradingProperties(
    val runtimeProfile: String = "local-trusted",
    /** Set only when the grading-strong runtime passed the 17 checks; nothing sets it today (D-10). */
    val isolationVerified: Boolean = false,
    val allowUnverifiedIsolation: Boolean = false,
    val timeoutSeconds: Int = 120,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GradingProperties::class)
class GradingConfig

@Component
class GradingSafetyCheck(private val environment: Environment, private val properties: GradingProperties) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        check(!properties.isolationVerified || properties.runtimeProfile == "grading-strong") {
            "Unsafe grading configuration: only the grading-strong profile can be marked isolation-verified"
        }
        if ("prod" in environment.activeProfiles) {
            check(!properties.allowUnverifiedIsolation) { "Unsafe grading configuration: prod cannot grade on unverified isolation" }
        }
    }
}
