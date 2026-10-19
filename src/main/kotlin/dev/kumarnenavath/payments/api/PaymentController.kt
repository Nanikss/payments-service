package dev.kumarnenavath.payments.api

import dev.kumarnenavath.payments.domain.Dispute
import dev.kumarnenavath.payments.domain.Payment
import dev.kumarnenavath.payments.domain.PaymentStatus
import dev.kumarnenavath.payments.domain.Refund
import dev.kumarnenavath.payments.idempotency.IdempotencyService
import dev.kumarnenavath.payments.service.CreatePayment
import dev.kumarnenavath.payments.service.PaymentService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class CreatePaymentRequest(
    @field:Positive
    val amountMinor: Long,
    @field:Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO-4217 code")
    val currency: String,
    @field:NotBlank
    @field:Size(max = 64)
    val merchantReference: String,
    /** false = authorize only; capture later with POST /{id}/capture. */
    val capture: Boolean = true,
)

data class CreateRefundRequest(
    @field:Positive
    val amountMinor: Long,
    @field:Size(max = 255)
    val reason: String? = null,
)

data class RefundResponse(val id: String, val paymentId: String, val amountMinor: Long, val status: String, val reason: String?, val createdAt: Instant) {
    companion object {
        fun from(r: Refund) = RefundResponse(r.id, r.paymentId, r.amountMinor, r.status.name, r.reason, r.createdAt)
    }
}

data class DisputeResponse(val id: String, val amountMinor: Long, val status: String, val reason: String?, val createdAt: Instant) {
    companion object {
        fun from(d: Dispute) = DisputeResponse(d.id, d.amountMinor, d.status.name, d.reason, d.createdAt)
    }
}

data class PaymentResponse(
    val id: String,
    val merchantReference: String,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val capturedMinor: Long,
    val refundedMinor: Long,
    val pendingRefundMinor: Long,
    val refundableMinor: Long,
    val pspReference: String?,
    val failureCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val refunds: List<RefundResponse>,
    val disputes: List<DisputeResponse>,
) {
    companion object {
        fun from(p: Payment, refunds: List<Refund> = emptyList(), disputes: List<Dispute> = emptyList()) = PaymentResponse(
            id = p.id,
            merchantReference = p.merchantReference,
            status = p.status.name,
            amountMinor = p.amountMinor,
            currency = p.currency,
            capturedMinor = p.capturedMinor,
            refundedMinor = p.refundedMinor,
            pendingRefundMinor = p.pendingRefundMinor,
            refundableMinor = p.refundableMinor,
            pspReference = p.pspReference,
            failureCode = p.failureCode,
            createdAt = p.createdAt,
            updatedAt = p.updatedAt,
            refunds = refunds.map(RefundResponse::from),
            disputes = disputes.map(DisputeResponse::from),
        )
    }
}

@RestController
@RequestMapping("/api/v2/payments")
class PaymentController(
    private val payments: PaymentService,
    private val idempotency: IdempotencyService,
) {
    @PostMapping
    fun create(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreatePaymentRequest,
    ): ResponseEntity<*> = idempotency.execute("POST /api/v2/payments", idempotencyKey, request) {
        val payment = payments.create(
            CreatePayment(request.amountMinor, request.currency, request.merchantReference, request.capture),
            idempotencyKey,
        )
        ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): PaymentResponse =
        PaymentResponse.from(payments.get(id), payments.refundsOf(id), payments.disputesOf(id))

    @GetMapping
    fun list(
        @RequestParam(required = false) status: PaymentStatus?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): List<PaymentResponse> = payments.list(status, limit).map { PaymentResponse.from(it) }

    @PostMapping("/{id}/capture")
    fun capture(@PathVariable id: String): PaymentResponse = PaymentResponse.from(payments.capture(id))

    @PostMapping("/{id}/void")
    fun void(@PathVariable id: String): PaymentResponse = PaymentResponse.from(payments.void(id))

    @PostMapping("/{id}/refunds")
    fun refund(
        @PathVariable id: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreateRefundRequest,
    ): ResponseEntity<*> = idempotency.execute("POST /api/v2/payments/$id/refunds", idempotencyKey, request) {
        val refund = payments.refund(id, request.amountMinor, request.reason, idempotencyKey)
        ResponseEntity.status(HttpStatus.CREATED).body(RefundResponse.from(refund))
    }
}
