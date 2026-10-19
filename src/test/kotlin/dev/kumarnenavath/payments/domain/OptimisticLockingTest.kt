package dev.kumarnenavath.payments.domain

import dev.kumarnenavath.payments.support.ApiTestBase
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.time.Instant

class OptimisticLockingTest : ApiTestBase() {

    @Autowired
    lateinit var payments: PaymentRepository

    @Test
    fun `a stale copy of a payment cannot overwrite a newer one`() {
        val id = createPayment(amountMinor = 10_000)["id"].asText()

        // Two requests read the same version...
        val first = payments.findById(id).orElseThrow()
        val second = payments.findById(id).orElseThrow()

        // ...the first commits a refund reservation...
        first.startRefund(6_000, Instant.now())
        payments.save(first)

        // ...so the second, still based on the old version, is rejected instead of double-refunding.
        second.startRefund(6_000, Instant.now())
        assertThatThrownBy { payments.save(second) }.isInstanceOf(ObjectOptimisticLockingFailureException::class.java)
    }
}
