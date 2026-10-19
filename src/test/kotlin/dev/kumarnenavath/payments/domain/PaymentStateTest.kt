package dev.kumarnenavath.payments.domain

import dev.kumarnenavath.payments.common.InvalidAmountException
import dev.kumarnenavath.payments.common.InvalidStateException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class PaymentStateTest {
    private val now = Instant.parse("2026-10-01T10:00:00Z")

    private fun captured(amount: Long = 10_000) =
        Payment(id = "pay_1", merchantReference = "order-1", amountMinor = amount, currency = "USD", createdAt = now).apply {
            markAuthorized("psp_ch_1", now)
            capture(now)
        }

    @Test
    fun `only an authorized payment can be captured or voided`() {
        val pending = Payment(id = "pay_1", merchantReference = "o", amountMinor = 500, currency = "USD", createdAt = now)
        assertThatThrownBy { pending.capture(now) }.isInstanceOf(InvalidStateException::class.java)

        val payment = captured()
        assertThat(payment.status).isEqualTo(PaymentStatus.CAPTURED)
        assertThat(payment.capturedMinor).isEqualTo(10_000)
        assertThatThrownBy { payment.void(now) }.isInstanceOf(InvalidStateException::class.java)
        assertThatThrownBy { payment.capture(now) }.isInstanceOf(InvalidStateException::class.java)
    }

    @Test
    fun `refund reserves the amount until it settles`() {
        val payment = captured()
        payment.startRefund(4_000, now)

        assertThat(payment.pendingRefundMinor).isEqualTo(4_000)
        assertThat(payment.refundableMinor).isEqualTo(6_000)
        assertThat(payment.status).isEqualTo(PaymentStatus.CAPTURED)

        payment.settleRefund(4_000, succeeded = true, now)
        assertThat(payment.status).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED)
        assertThat(payment.refundedMinor).isEqualTo(4_000)
        assertThat(payment.pendingRefundMinor).isZero()

        payment.startRefund(6_000, now)
        payment.settleRefund(6_000, succeeded = true, now)
        assertThat(payment.status).isEqualTo(PaymentStatus.REFUNDED)
        assertThat(payment.refundableMinor).isZero()
    }

    @Test
    fun `cannot refund more than what is captured minus refunded and pending`() {
        val payment = captured()
        payment.startRefund(7_000, now)

        assertThatThrownBy { payment.startRefund(3_001, now) }
            .isInstanceOf(InvalidAmountException::class.java)
            .hasMessageContaining("exceeds refundable amount 3000")
        assertThatThrownBy { payment.startRefund(0, now) }.isInstanceOf(InvalidAmountException::class.java)
    }

    @Test
    fun `a failed refund releases the reserved amount`() {
        val payment = captured()
        payment.startRefund(10_000, now)
        payment.settleRefund(10_000, succeeded = false, now)

        assertThat(payment.status).isEqualTo(PaymentStatus.CAPTURED)
        assertThat(payment.refundableMinor).isEqualTo(10_000)
    }

    @Test
    fun `a won dispute restores the settled status and a lost one is a chargeback`() {
        val won = captured().apply {
            startRefund(2_500, now)
            settleRefund(2_500, succeeded = true, now)
            openDispute(now)
        }
        assertThat(won.status).isEqualTo(PaymentStatus.DISPUTED)
        won.closeDispute(won = true, now)
        assertThat(won.status).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED)

        val lost = captured().apply { openDispute(now) }
        lost.closeDispute(won = false, now)
        assertThat(lost.status).isEqualTo(PaymentStatus.CHARGED_BACK)
    }

    @Test
    fun `refunds are blocked while a dispute is open`() {
        val payment = captured().apply { openDispute(now) }
        assertThatThrownBy { payment.startRefund(100, now) }
            .isInstanceOf(InvalidStateException::class.java)
            .hasMessageContaining("DISPUTED")
    }
}
