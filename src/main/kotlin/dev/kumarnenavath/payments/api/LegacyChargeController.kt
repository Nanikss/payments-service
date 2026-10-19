package dev.kumarnenavath.payments.api

import dev.kumarnenavath.payments.domain.Money
import dev.kumarnenavath.payments.domain.Payment
import dev.kumarnenavath.payments.domain.PaymentStatus
import dev.kumarnenavath.payments.service.CreatePayment
import dev.kumarnenavath.payments.service.PaymentService
import io.micrometer.core.instrument.MeterRegistry
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class LegacyChargeRequest(
    @field:DecimalMin(value = "0.01")
    val amount: BigDecimal,
    @field:NotBlank
    val currency: String,
    @field:NotBlank
    @field:Size(max = 64)
    val reference: String,
)

/** The v1 response shape that existing consumers depend on: decimal amount as a string, lowercase status. */
data class LegacyChargeResponse(val id: String, val amount: String, val currency: String, val status: String, val reference: String) {
    companion object {
        fun from(p: Payment) = LegacyChargeResponse(
            id = p.id,
            amount = Money.toMajor(p.amountMinor, p.currency).toPlainString(),
            currency = p.currency,
            status = when (p.status) {
                PaymentStatus.PENDING -> "pending"
                PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED -> "succeeded"
                PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED -> "refunded"
                PaymentStatus.DISPUTED, PaymentStatus.CHARGED_BACK -> "disputed"
                PaymentStatus.FAILED, PaymentStatus.VOIDED -> "failed"
            },
            reference = p.merchantReference,
        )
    }
}

/**
 * Deprecated v1 API, kept as a thin adapter over the v2 service so legacy consumers keep working while
 * they migrate. Every response carries `Deprecation`, `Sunset` and a `successor-version` link
 * (RFC 9745 / RFC 8594), and a usage counter shows who still calls it before the sunset date.
 */
@RestController
@RequestMapping("/api/v1/charges")
class LegacyChargeController(
    private val payments: PaymentService,
    private val meters: MeterRegistry,
) {
    @PostMapping
    fun create(@Valid @RequestBody request: LegacyChargeRequest): ResponseEntity<LegacyChargeResponse> {
        meters.counter("payments.api.v1.requests", "endpoint", "create").increment()
        val payment = payments.create(
            CreatePayment(Money.toMinor(request.amount, request.currency), request.currency, request.reference, capture = true),
            idempotencyKey = "v1-" + UUID.randomUUID(), // v1 never supported idempotency keys
        )
        return deprecated(ResponseEntity.status(HttpStatus.CREATED)).body(LegacyChargeResponse.from(payment))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<LegacyChargeResponse> {
        meters.counter("payments.api.v1.requests", "endpoint", "get").increment()
        return deprecated(ResponseEntity.ok()).body(LegacyChargeResponse.from(payments.get(id)))
    }

    private fun deprecated(builder: ResponseEntity.BodyBuilder): ResponseEntity.BodyBuilder = builder
        .header("Deprecation", "@${DEPRECATED_AT.epochSecond}")
        .header("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(SUNSET_AT.atOffset(ZoneOffset.UTC)))
        .header("Link", "</api/v2/payments>; rel=\"successor-version\"")

    companion object {
        val DEPRECATED_AT: Instant = Instant.parse("2026-09-01T00:00:00Z")
        val SUNSET_AT: Instant = Instant.parse("2027-03-31T00:00:00Z")
    }
}
