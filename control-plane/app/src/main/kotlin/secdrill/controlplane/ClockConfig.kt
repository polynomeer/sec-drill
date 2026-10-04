package secdrill.controlplane

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Single time source so expiry logic is testable with a controlled clock (26). */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()
}
