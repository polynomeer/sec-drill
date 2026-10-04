package secdrill.controlplane.platform

import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.Declarables
import org.springframework.amqp.core.FanoutExchange
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.core.TopicExchange
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import secdrill.kernel.EventType

/**
 * Broker topology (16, ADR 0004). Events go to a topic exchange keyed by event type. Consumer queues are quorum
 * queues with a delivery limit; messages that keep failing or are rejected as poison go to the DLQ through the
 * dead-letter exchange. Redrive keeps the original event id (16).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AsyncProperties::class)
class RabbitTopology {
    companion object {
        const val EVENTS_EXCHANGE = "secdrill.events"
        const val DEAD_LETTER_EXCHANGE = "secdrill.dlx"
        const val DEAD_LETTER_QUEUE = "secdrill.dlq"
        const val GRADING_QUEUE = "grading.official"
        const val DELIVERY_LIMIT = 3
    }

    @Bean
    fun declarables(): Declarables {
        val events = TopicExchange(EVENTS_EXCHANGE, true, false)
        val deadLetters = FanoutExchange(DEAD_LETTER_EXCHANGE, true, false)
        val dlq = QueueBuilder.durable(DEAD_LETTER_QUEUE).quorum().build()
        val grading = QueueBuilder.durable(GRADING_QUEUE).quorum()
            .deliveryLimit(DELIVERY_LIMIT)
            .deadLetterExchange(DEAD_LETTER_EXCHANGE)
            .build()
        val bindings: List<Binding> = listOf(
            BindingBuilder.bind(dlq).to(deadLetters),
            BindingBuilder.bind(grading).to(events).with(EventType.SubmissionAccepted.name),
        )
        return Declarables(listOf(events, deadLetters, dlq, grading) + bindings)
    }
}
