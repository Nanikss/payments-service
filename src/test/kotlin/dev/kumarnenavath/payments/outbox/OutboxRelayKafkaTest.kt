package dev.kumarnenavath.payments.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import dev.kumarnenavath.payments.service.CreatePayment
import dev.kumarnenavath.payments.service.PaymentService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import java.util.UUID

@SpringBootTest(
    properties = [
        "payments.outbox.relay.enabled=true",
        "payments.outbox.relay.poll-interval-ms=100",
        "payments.outbox.topic=payment-events-it",
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
    ],
)
@EmbeddedKafka(partitions = 1)
class OutboxRelayKafkaTest {

    @Autowired
    lateinit var broker: EmbeddedKafkaBroker

    @Autowired
    lateinit var payments: PaymentService

    @Autowired
    lateinit var outbox: OutboxRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Test
    fun `relays outbox events to Kafka in order, keyed by payment id`() {
        val payment = payments.create(CreatePayment(4_200, "USD", "order-kafka"), UUID.randomUUID().toString())
        payments.refund(payment.id, 1_000, "damaged", UUID.randomUUID().toString())

        val consumerProps = KafkaTestUtils.consumerProps("relay-test-" + UUID.randomUUID(), "false", broker)
        val consumer = DefaultKafkaConsumerFactory(consumerProps, StringDeserializer(), StringDeserializer()).createConsumer()
        consumer.subscribe(listOf("payment-events-it"))

        val received = mutableListOf<ConsumerRecord<String, String>>()
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (received.count { it.key() == payment.id } < 3 && System.nanoTime() < deadline) {
            received += consumer.poll(Duration.ofMillis(250))
        }
        consumer.close()

        val types = received.filter { it.key() == payment.id }.map { objectMapper.readTree(it.value())["type"].asText() }
        assertThat(types).containsExactly("payment.authorized", "payment.captured", "refund.pending")
        assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(payment.id)).allSatisfy { assertThat(it.publishedAt).isNotNull() }
    }
}
