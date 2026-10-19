package dev.kumarnenavath.payments.graphql

import dev.kumarnenavath.payments.domain.Dispute
import dev.kumarnenavath.payments.domain.Payment
import dev.kumarnenavath.payments.domain.PaymentStatus
import dev.kumarnenavath.payments.domain.Refund
import dev.kumarnenavath.payments.service.PaymentService
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * Read-only GraphQL API for internal consumers (support tooling, finance dashboards, reconciliation)
 * that each need different slices of a payment. `@BatchMapping` loads refunds and disputes for a whole page
 * of payments in one query each, instead of one query per payment (the N+1 problem).
 */
@Controller
class PaymentGraphQlController(private val service: PaymentService) {

    @QueryMapping
    fun payment(@Argument id: String): Payment? = service.find(id)

    @QueryMapping
    fun payments(@Argument status: PaymentStatus?, @Argument limit: Int?): List<Payment> =
        service.list(status, limit ?: 20)

    @BatchMapping
    fun refunds(page: List<Payment>): Map<Payment, List<Refund>> {
        val byPayment = service.refundsFor(page.map { it.id }).groupBy { it.paymentId }
        return page.associateWith { byPayment[it.id].orEmpty().sortedBy(Refund::createdAt) }
    }

    @BatchMapping
    fun disputes(page: List<Payment>): Map<Payment, List<Dispute>> {
        val byPayment = service.disputesFor(page.map { it.id }).groupBy { it.paymentId }
        return page.associateWith { byPayment[it.id].orEmpty().sortedBy(Dispute::createdAt) }
    }
}
