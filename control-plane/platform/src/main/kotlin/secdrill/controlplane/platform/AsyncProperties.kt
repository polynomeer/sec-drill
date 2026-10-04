package secdrill.controlplane.platform

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Timing assumptions from 00/16. They are MVP assumptions to be measured (27), not verified limits. */
@ConfigurationProperties("secdrill.async")
data class AsyncProperties(
    /** Background loops (publisher, job sweeper). Tests turn this off and drive the loops step by step. */
    val schedulingEnabled: Boolean = true,
    val publisherBatchSize: Int = 50,
    val publisherInterval: Duration = Duration.ofMillis(500),
    val confirmTimeout: Duration = Duration.ofSeconds(5),
    val publishBackoffMax: Duration = Duration.ofMinutes(1),
    val lease: Duration = Duration.ofSeconds(30),
    val dispatchTimeout: Duration = Duration.ofSeconds(120),
    /** Total attempts including the first (00). */
    val maxAttempts: Int = 3,
    /** Delay before attempt 2 and attempt 3 (16), plus up to [retryJitter]. */
    val retryDelays: List<Duration> = listOf(Duration.ofSeconds(5), Duration.ofSeconds(20)),
    val retryJitter: Duration = Duration.ofSeconds(1),
    val sweepInterval: Duration = Duration.ofSeconds(1),
)
