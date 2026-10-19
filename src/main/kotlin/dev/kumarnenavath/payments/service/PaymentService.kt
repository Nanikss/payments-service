package dev.kumarnenavath.payments.service

import dev.kumarnenavath.payments.common.Ids
import dev.kumarnenavath.payments.common.InvalidAmountException
import dev.kumarnenavath.payments.common.NotFoundException
import dev.kumarnenavath.payments.domain.Dispute
import dev.kumarnenavath.payments.domain.DisputeRepository
import dev.kumarnenavath.payments.domain.DisputeStatus
import dev.kumarnenavath.payments.domain.Money
import dev.kumarnenavath.payments.domain.Payment
import dev.kumarnenavath.payments.domain.PaymentRepository
import dev.kumarnenavath.payments.domain.PaymentStatus
import dev.kumarnenavath.payments.domain.Refund
import dev.kumarnenavath.payments.domain.RefundRepository
import dev.kumarnenavath.payments.domain.RefundStatus
import dev.kumarnenavath.payments.outbox.OutboxWriter
import dev.kumarnenavath.payments.psp.AuthorizationRequest
import dev.kumarnenavath.payments.psp.AuthorizationResult
import dev.kumarnenavath.payments.psp.PspClient
import dev.kumarnenavath.payments.psp.PspUnavailableException
import dev.kumarnenavath.payments.psp.RefundRequest
import dev.kumarnenavath.payments.psp.Retry
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock

data class CreatePayment(
    val amountMinor: Long,
    val currency: String,
    val merchantReference: String,
    val capture: Boolean = true,
)

data class DisputeNotice(
    val pspDisputeId: String,
    val chargeReference: String,
    val amountMinor: Long,
    val reason: String?,
)

/**
 * Payment use cases. PSP calls never happen inside a database transaction: the state is written,
 * the transaction commits, the PSP is called, then a second short transaction records the result.
 * That keeps row locks short and means a slow gateway can't exhaust the connection pool.
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val refunds: RefundRepository,
    private val disputes: DisputeRepository,
    private val psp: PspClient,
    private val retry: Retry,
    private val outbox: OutboxWriter,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val meters: MeterRegistry,
) {

    fun create(command: CreatePayment, idempotencyKey: String): Payment {
        val currency = Money.currency(command.currency).currencyCode
        if (command.amountMinor <= 0) throw InvalidAmountException("amountMinor must be positive")

        val pending = inTx {
            payments.save(
                Payment(
                    id = Ids.new("pay"),
                    merchantReference = command.merchantReference,
                    amountMinor = command.amountMinor,
                    currency = currency,
                    createdAt = clock.instant(),
                ),
            )
        }

        val result = try {
            retry.call("authorize") {
                psp.authorize(AuthorizationRequest(pending.id, pending.amountMinor, currency, idempotencyKey))
            }
        } catch (e: PspUnavailableException) {
            inTx {
                load(pending.id).also {
                    it.markFailed("psp_unavailable", clock.instant())
                    outbox.record(it, "payment.failed")
                }
            }
            meters.counter("payments.created", "outcome", "psp_unavailable").increment()
            throw e
        }

        val authorized = inTx {
            load(pending.id).also {
                when (result) {
                    is AuthorizationResult.Approved -> {
                        it.markAuthorized(result.pspReference, clock.instant())
                        outbox.record(it, "payment.authorized")
                    }
                    is AuthorizationResult.Declined -> {
                        it.markFailed(result.code, clock.instant())
                        outbox.record(it, "payment.failed", mapOf("failureCode" to result.code))
                    }
                }
            }
        }
        meters.counter("payments.created", "outcome", authorized.status.name.lowercase()).increment()

        return if (command.capture && authorized.status == PaymentStatus.AUTHORIZED) capture(authorized.id) else authorized
    }

    fun capture(paymentId: String): Payment {
        val payment = inTx { load(paymentId).also { it.ensureCapturable() } }
        retry.call("capture") { psp.capture(payment.pspReference!!, payment.amountMinor, payment.currency) }
        return inTx {
            load(paymentId).also {
                it.capture(clock.instant())
                outbox.record(it, "payment.captured")
            }
        }
    }

    fun void(paymentId: String): Payment {
        val payment = inTx { load(paymentId).also { it.ensureVoidable() } }
        retry.call("void") { psp.void(payment.pspReference!!) }
        return inTx {
            load(paymentId).also {
                it.void(clock.instant())
                outbox.record(it, "payment.voided")
            }
        }
    }

    fun refund(paymentId: String, amountMinor: Long, reason: String?, idempotencyKey: String): Refund {
        // Reserve the amount first. The payment row's @Version turns a concurrent second refund into a 409
        // instead of letting both pass the refundable check.
        val (refund, payment) = inTx {
            val payment = load(paymentId)
            payment.startRefund(amountMinor, clock.instant())
            val refund = refunds.save(
                Refund(id = Ids.new("re"), paymentId = paymentId, amountMinor = amountMinor, reason = reason, createdAt = clock.instant()),
            )
            outbox.record(payment, "refund.pending", mapOf("refundId" to refund.id, "refundAmountMinor" to amountMinor))
            refund to payment
        }

        val pspRefundReference = try {
            retry.call("refund") {
                psp.refund(RefundRequest(refund.id, payment.pspReference!!, amountMinor, payment.currency, idempotencyKey))
            }
        } catch (e: PspUnavailableException) {
            inTx { settle(refund.id, succeeded = false) }
            throw e
        }

        return inTx {
            refunds.findById(refund.id).orElseThrow().also { it.pspReference = pspRefundReference }
        }
    }

    /** Called from the refund webhook. Returns null for refunds we don't know about. */
    @Transactional
    fun settleRefund(refundId: String, succeeded: Boolean): Refund? =
        if (refunds.existsById(refundId)) settle(refundId, succeeded) else null

    @Transactional
    fun disputeOpened(notice: DisputeNotice): Dispute? {
        val payment = payments.findByPspReference(notice.chargeReference) ?: return null
        disputes.findByPspDisputeId(notice.pspDisputeId)?.let { return it }
        payment.openDispute(clock.instant())
        val dispute = disputes.save(newDispute(payment, notice))
        outbox.record(payment, "dispute.opened", mapOf("disputeId" to dispute.id, "disputeAmountMinor" to notice.amountMinor))
        return dispute
    }

    /** Handles `dispute.closed` even when the matching `dispute.opened` never arrived (webhooks aren't ordered). */
    @Transactional
    fun disputeClosed(notice: DisputeNotice, won: Boolean): Dispute? {
        val payment = payments.findByPspReference(notice.chargeReference) ?: return null
        val dispute = disputes.findByPspDisputeId(notice.pspDisputeId) ?: disputes.save(newDispute(payment, notice))
        if (dispute.status != DisputeStatus.OPEN) return dispute
        dispute.status = if (won) DisputeStatus.WON else DisputeStatus.LOST
        dispute.updatedAt = clock.instant()
        payment.closeDispute(won, clock.instant())
        outbox.record(payment, if (won) "dispute.won" else "dispute.lost", mapOf("disputeId" to dispute.id))
        return dispute
    }

    @Transactional(readOnly = true)
    fun get(paymentId: String): Payment = load(paymentId)

    @Transactional(readOnly = true)
    fun find(paymentId: String): Payment? = payments.findById(paymentId).orElse(null)

    @Transactional(readOnly = true)
    fun list(status: PaymentStatus?, limit: Int): List<Payment> {
        val page = PageRequest.of(0, limit.coerceIn(1, 100))
        return if (status == null) payments.findAllByOrderByCreatedAtDesc(page) else payments.findByStatusOrderByCreatedAtDesc(status, page)
    }

    @Transactional(readOnly = true)
    fun refundsOf(paymentId: String): List<Refund> = refunds.findByPaymentIdOrderByCreatedAt(paymentId)

    @Transactional(readOnly = true)
    fun disputesOf(paymentId: String): List<Dispute> = disputes.findByPaymentIdOrderByCreatedAt(paymentId)

    @Transactional(readOnly = true)
    fun refundsFor(paymentIds: Collection<String>): List<Refund> = refunds.findByPaymentIdIn(paymentIds)

    @Transactional(readOnly = true)
    fun disputesFor(paymentIds: Collection<String>): List<Dispute> = disputes.findByPaymentIdIn(paymentIds)

    private fun settle(refundId: String, succeeded: Boolean): Refund {
        val refund = refunds.findById(refundId).orElseThrow()
        if (refund.status != RefundStatus.PENDING) return refund // duplicate or late webhook: already settled
        val payment = load(refund.paymentId)
        payment.settleRefund(refund.amountMinor, succeeded, clock.instant())
        refund.status = if (succeeded) RefundStatus.SUCCEEDED else RefundStatus.FAILED
        refund.updatedAt = clock.instant()
        outbox.record(payment, if (succeeded) "refund.succeeded" else "refund.failed", mapOf("refundId" to refund.id))
        return refund
    }

    private fun newDispute(payment: Payment, notice: DisputeNotice) = Dispute(
        id = Ids.new("dp"),
        paymentId = payment.id,
        pspDisputeId = notice.pspDisputeId,
        amountMinor = notice.amountMinor,
        reason = notice.reason,
        createdAt = clock.instant(),
    )

    private fun load(paymentId: String): Payment =
        payments.findById(paymentId).orElseThrow { NotFoundException("Payment", paymentId) }

    private fun <T : Any> inTx(block: () -> T): T = tx.execute { block() }!!
}
