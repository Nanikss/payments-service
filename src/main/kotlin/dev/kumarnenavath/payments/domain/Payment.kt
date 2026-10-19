package dev.kumarnenavath.payments.domain

import dev.kumarnenavath.payments.common.InvalidAmountException
import dev.kumarnenavath.payments.common.InvalidStateException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

enum class PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED,
    FAILED,
    DISPUTED,
    CHARGED_BACK,
}

/**
 * A card payment and its state machine. All transitions go through methods on this class so the rules
 * (what can be captured, how much can still be refunded, what a dispute does) live in one place.
 *
 * Refunds are asynchronous: [startRefund] reserves the amount immediately, so two concurrent refunds can't
 * both pass the "refundable" check, and the PSP webhook later settles it with [settleRefund].
 */
@Entity
@Table(name = "payments")
class Payment(
    @Id
    val id: String,
    @Column(name = "merchant_reference")
    val merchantReference: String,
    @Column(name = "amount_minor")
    val amountMinor: Long,
    val currency: String,
    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.PENDING,
    @Column(name = "psp_reference")
    var pspReference: String? = null,
    @Column(name = "captured_minor")
    var capturedMinor: Long = 0,
    @Column(name = "refunded_minor")
    var refundedMinor: Long = 0,
    @Column(name = "pending_refund_minor")
    var pendingRefundMinor: Long = 0,
    @Column(name = "failure_code")
    var failureCode: String? = null,
    @Column(name = "created_at")
    val createdAt: Instant,
    @Column(name = "updated_at")
    var updatedAt: Instant = createdAt,
) {
    @Version
    var version: Long? = null

    val refundableMinor: Long
        get() = capturedMinor - refundedMinor - pendingRefundMinor

    fun markAuthorized(pspReference: String, now: Instant) {
        expect(PaymentStatus.PENDING, "authorize")
        this.pspReference = pspReference
        transition(PaymentStatus.AUTHORIZED, now)
    }

    fun markFailed(failureCode: String, now: Instant) {
        expect(PaymentStatus.PENDING, "fail")
        this.failureCode = failureCode
        transition(PaymentStatus.FAILED, now)
    }

    fun ensureCapturable() = expect(PaymentStatus.AUTHORIZED, "capture")

    fun capture(now: Instant) {
        ensureCapturable()
        capturedMinor = amountMinor
        transition(PaymentStatus.CAPTURED, now)
    }

    fun ensureVoidable() = expect(PaymentStatus.AUTHORIZED, "void")

    fun void(now: Instant) {
        ensureVoidable()
        transition(PaymentStatus.VOIDED, now)
    }

    fun startRefund(amountMinor: Long, now: Instant) {
        if (status != PaymentStatus.CAPTURED && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw InvalidStateException("Cannot refund payment $id in status $status")
        }
        if (amountMinor <= 0) throw InvalidAmountException("Refund amount must be positive")
        if (amountMinor > refundableMinor) {
            throw InvalidAmountException("Refund of $amountMinor exceeds refundable amount $refundableMinor for payment $id")
        }
        pendingRefundMinor += amountMinor
        updatedAt = now
    }

    fun settleRefund(amountMinor: Long, succeeded: Boolean, now: Instant) {
        pendingRefundMinor -= amountMinor
        if (succeeded) refundedMinor += amountMinor
        if (status != PaymentStatus.DISPUTED && status != PaymentStatus.CHARGED_BACK) status = settledStatus()
        updatedAt = now
    }

    /** Disputes arrive by webhook, so these are tolerant of odd orderings rather than throwing. */
    fun openDispute(now: Instant) {
        if (status in setOf(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED)) {
            transition(PaymentStatus.DISPUTED, now)
        }
    }

    fun closeDispute(won: Boolean, now: Instant) {
        transition(if (won) settledStatus() else PaymentStatus.CHARGED_BACK, now)
    }

    private fun settledStatus() = when {
        refundedMinor == 0L -> PaymentStatus.CAPTURED
        refundedMinor >= capturedMinor -> PaymentStatus.REFUNDED
        else -> PaymentStatus.PARTIALLY_REFUNDED
    }

    private fun expect(required: PaymentStatus, action: String) {
        if (status != required) throw InvalidStateException("Cannot $action payment $id in status $status")
    }

    private fun transition(next: PaymentStatus, now: Instant) {
        status = next
        updatedAt = now
    }
}
