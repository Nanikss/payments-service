package dev.kumarnenavath.payments.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import dev.kumarnenavath.payments.common.Ids
import dev.kumarnenavath.payments.config.PaymentsProperties
import dev.kumarnenavath.payments.domain.Payment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit

@Entity
@Table(name = "outbox_events")
class OutboxEvent(
    @Id
    val id: String,
    @Column(name = "aggregate_id")
    val aggregateId: String,
    @Column(name = "event_type")
    val eventType: String,
    @Column(columnDefinition = "TEXT")
    val payload: String,
    @Column(name = "created_at")
    val createdAt: Instant,
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
    var attempts: Int = 0,
) {
    @Version
    var version: Long? = null
}

interface OutboxRepository : JpaRepository<OutboxEvent, String> {
    fun findByPublishedAtIsNullOrderByCreatedAtAsc(pageable: Pageable): List<OutboxEvent>
    fun findByAggregateIdOrderByCreatedAtAsc(aggregateId: String): List<OutboxEvent>
    fun countByPublishedAtIsNull(): Long
}

/**
 * Writes domain events into the same database transaction as the state change (transactional outbox),
 * so an event is published if and only if the change committed. `MANDATORY` makes calling this outside a
 * transaction a bug that fails fast instead of silently writing an event for a rolled-back change.
 */
@Component
class OutboxWriter(
    private val repository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(payment: Payment, type: String, extra: Map<String, Any?> = emptyMap()) {
        val now = clock.instant()
        val id = Ids.new("evt")
        val payload = linkedMapOf<String, Any?>(
            "eventId" to id,
            "type" to type,
            "occurredAt" to now.toString(),
            "paymentId" to payment.id,
            "status" to payment.status.name,
            "amountMinor" to payment.amountMinor,
            "currency" to payment.currency,
            "capturedMinor" to payment.capturedMinor,
            "refundedMinor" to payment.refundedMinor,
        ) + extra
        repository.save(OutboxEvent(id, payment.id, type, objectMapper.writeValueAsString(payload), now))
    }
}

/**
 * Polls unpublished events and sends them to Kafka (at-least-once; consumers de-duplicate on `eventId`).
 * It stops at the first failure so later events for the same payment are never published ahead of earlier ones.
 */
@Component
@ConditionalOnProperty("payments.outbox.relay.enabled", havingValue = "true")
class OutboxRelay(
    private val repository: OutboxRepository,
    private val kafka: KafkaTemplate<String, String>,
    private val properties: PaymentsProperties,
    private val clock: Clock,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${payments.outbox.relay.poll-interval-ms:500}")
    fun relay() {
        publishBatch()
    }

    fun publishBatch(): Int = tx.execute {
        val batch = repository.findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.of(0, properties.outbox.relay.batchSize))
        var sent = 0
        for (event in batch) {
            try {
                kafka.send(properties.outbox.topic, event.aggregateId, event.payload).get(10, TimeUnit.SECONDS)
                event.publishedAt = clock.instant()
                sent++
            } catch (e: Exception) {
                event.attempts++
                log.warn("outbox publish failed event={} attempts={}: {}", event.id, event.attempts, e.message)
                break
            }
        }
        sent
    } ?: 0
}
