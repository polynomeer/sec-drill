package secdrill.controlplane

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import secdrill.controlplane.platform.AsyncProperties
import secdrill.execution.fake.FakeGradingWorker
import secdrill.execution.protocol.JobControl
import secdrill.kernel.Verdict

/** In-process fake grading worker for local development (prompt 04). Not a strong runtime; results are labelled fake. */
@ConfigurationProperties("secdrill.execution.fake-worker")
data class FakeWorkerProperties(val enabled: Boolean = false, val verdict: Verdict = Verdict.FAIL)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FakeWorkerProperties::class)
class FakeWorkerConfig {
    @Bean
    @ConditionalOnProperty("secdrill.execution.fake-worker.enabled", havingValue = "true")
    fun fakeGradingWorker(control: JobControl, properties: FakeWorkerProperties): FakeGradingWorker {
        require(properties.verdict == Verdict.PASS || properties.verdict == Verdict.FAIL) { "fake verdict must be PASS or FAIL" }
        return FakeGradingWorker(control, "fake-worker-local") { FakeGradingWorker.FakeStep.Complete(properties.verdict) }
    }

    @Component
    @ConditionalOnProperty("secdrill.execution.fake-worker.enabled", havingValue = "true")
    class FakeWorkerLoop(private val worker: FakeGradingWorker, private val async: AsyncProperties) {
        private val log = LoggerFactory.getLogger(javaClass)

        @Scheduled(fixedDelay = 1000)
        fun poll() {
            if (!async.schedulingEnabled) return
            runCatching { worker.runOnce() }.onFailure { log.warn("Fake worker iteration failed", it) }
        }
    }
}

/** A fake worker must never serve real learners: it requires the `local` profile and refuses `prod`. */
@Component
class FakeWorkerSafetyCheck(
    private val environment: Environment,
    private val properties: FakeWorkerProperties,
    private val workers: ObjectProvider<FakeGradingWorker>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val profiles = environment.activeProfiles.toSet()
        val enabled = properties.enabled || workers.ifAvailable != null
        check(!enabled || ("local" in profiles && "prod" !in profiles)) {
            "Unsafe execution configuration: secdrill.execution.fake-worker.enabled requires the 'local' profile and must not run with 'prod'"
        }
    }
}
