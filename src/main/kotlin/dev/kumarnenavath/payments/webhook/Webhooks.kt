package dev.kumarnenavath.payments.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.kumarnenavath.payments.common.ApiException
import dev.kumarnenavath.payments.config.PaymentsProperties
import dev.kumarnenavath.payments.service.DisputeNotice
import dev.kumarnenavath.payments.service.PaymentService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class InvalidSignatureException(message: String) :
    ApiException(HttpStatus.BAD_REQUEST, "invalid_signature", message)

/**
 * Verifies `Psp-Signature: t=<unix seconds>,v1=<hex hmac-sha256 of "t.body">`.
 *
 * - The HMAC covers the raw body, so the controller must not let Jackson re-serialize it first.
 * - The timestamp is signed too, and old timestamps are rejected, so a captured request can't be replayed later.
 * - Comparison is constant-time, and any configured secret is accepted, which allows zero-downtime rotation.
 */
@Component
class WebhookSignatureVerifier(
    private val properties: PaymentsProperties,
    private val clock: Clock,
) {
    fun verify(payload: String, header: String?) {
        if (header.isNullOrBlank()) throw InvalidSignatureException("Missing Psp-Signature header")
        val parts = header.split(",").mapNotNull { part ->
            part.trim().split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }
        val timestamp = parts.firstOrNull { it.first == "t" }?.second?.toLongOrNull()
            ?: throw InvalidSignatureException("Signature header has no timestamp")
        val signatures = parts.filter { it.first == "v1" }.map { it.second }
        if (signatures.isEmpty()) throw InvalidSignatureException("Signature header has no v1 signature")

        if (abs(clock.instant().epochSecond - timestamp) > properties.webhook.tolerance.seconds) {
            throw InvalidSignatureException("Signature timestamp is outside the allowed tolerance")
        }
        val valid = properties.webhook.secrets.any { secret ->
            val expected = sign(secret, timestamp, payload).toByteArray()
            signatures.any { MessageDigest.isEqual(it.toByteArray(), expected) }
        }
        if (!valid) throw InvalidSignatureException("Signature does not match")
    }

    companion object {
        fun sign(secret: String, timestamp: Long, payload: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal("$timestamp.$payload".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

data class PspEvent(
    val id: String,
    val type: String,
    val created: Long? = null,
    val data: Map<String, Any?> = emptyMap(),
)

@Entity
@Table(name = "webhook_events")
class ProcessedWebhookEvent(
    @Id
    @Column(name = "event_id")
    val eventId: String,
    @Column(name = "event_type")
    val eventType: String,
    var outcome: String,
    @Column(name = "received_at")
    val receivedAt: Instant,
) {
    @Version
    var version: Long? = null
}

interface ProcessedWebhookEventRepository : JpaRepository<ProcessedWebhookEvent, String>

enum class WebhookOutcome { APPLIED, IGNORED, DUPLICATE }

/**
 * PSPs deliver at least once and in no guaranteed order. Each event id is claimed with an INSERT in the same
 * transaction that applies it. A redelivery is answered with 200 `duplicate` and has no effect. If applying
 * fails, the claim rolls back too, so the PSP's retry processes it again.
 */
@Service
class WebhookService(
    private val processed: ProcessedWebhookEventRepository,
    private val payments: PaymentService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(payload: String): WebhookOutcome {
        val event = try {
            objectMapper.readValue<PspEvent>(payload)
        } catch (e: Exception) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_event", "Webhook body is not a valid event")
        }
        if (processed.existsById(event.id)) return WebhookOutcome.DUPLICATE

        val record = processed.saveAndFlush(ProcessedWebhookEvent(event.id, event.type, "PROCESSING", clock.instant()))
        val outcome = apply(event)
        record.outcome = outcome.name
        meters.counter("payments.webhooks", "type", event.type, "outcome", outcome.name.lowercase()).increment()
        log.info("webhook event={} type={} outcome={}", event.id, event.type, outcome)
        return outcome
    }

    private fun apply(event: PspEvent): WebhookOutcome {
        val applied = when (event.type) {
            "refund.succeeded" -> payments.settleRefund(event.string("refundId"), succeeded = true)
            "refund.failed" -> payments.settleRefund(event.string("refundId"), succeeded = false)
            "dispute.opened" -> payments.disputeOpened(event.disputeNotice())
            "dispute.closed" -> when (val status = event.string("status")) {
                "won" -> payments.disputeClosed(event.disputeNotice(), won = true)
                "lost" -> payments.disputeClosed(event.disputeNotice(), won = false)
                else -> throw invalid(event, "unknown dispute status '$status'")
            }
            else -> null
        }
        return if (applied == null) WebhookOutcome.IGNORED else WebhookOutcome.APPLIED
    }

    private fun PspEvent.disputeNotice() = DisputeNotice(
        pspDisputeId = string("disputeId"),
        chargeReference = string("chargeReference"),
        amountMinor = (data["amountMinor"] as? Number)?.toLong() ?: throw invalid(this, "missing amountMinor"),
        reason = data["reason"] as? String,
    )

    private fun PspEvent.string(field: String): String =
        data[field] as? String ?: throw invalid(this, "missing $field")

    private fun invalid(event: PspEvent, why: String) =
        ApiException(HttpStatus.BAD_REQUEST, "invalid_event", "Event ${event.id} (${event.type}): $why")
}

@RestController
class WebhookController(
    private val verifier: WebhookSignatureVerifier,
    private val service: WebhookService,
) {
    @PostMapping("/webhooks/psp", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestBody payload: String,
        @RequestHeader("Psp-Signature", required = false) signature: String?,
    ): Map<String, String> {
        verifier.verify(payload, signature)
        val outcome = try {
            service.handle(payload)
        } catch (e: DataIntegrityViolationException) {
            WebhookOutcome.DUPLICATE // a concurrent delivery of the same event won the INSERT
        }
        return mapOf("outcome" to outcome.name.lowercase())
    }
}
