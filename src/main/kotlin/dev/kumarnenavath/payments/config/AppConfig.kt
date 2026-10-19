package dev.kumarnenavath.payments.config

import dev.kumarnenavath.payments.psp.Retry
import graphql.scalars.ExtendedScalars
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import org.springframework.kafka.config.TopicBuilder
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentsProperties::class)
class AppConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun pspRetry(properties: PaymentsProperties): Retry =
        Retry(properties.psp.retry.maxAttempts, properties.psp.retry.backoff)

    /** GraphQL `Int` is 32-bit; money in minor units needs `Long`. */
    @Bean
    fun graphQlScalars(): RuntimeWiringConfigurer = RuntimeWiringConfigurer { it.scalar(ExtendedScalars.GraphQLLong) }

    /** Keyed by payment id, so all events for one payment land on one partition and stay in order. */
    @Bean
    @ConditionalOnProperty("payments.outbox.relay.enabled", havingValue = "true")
    fun paymentEventsTopic(properties: PaymentsProperties): NewTopic =
        TopicBuilder.name(properties.outbox.topic).partitions(6).replicas(1).build()
}
