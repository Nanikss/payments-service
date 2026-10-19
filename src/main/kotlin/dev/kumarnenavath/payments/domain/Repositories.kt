package dev.kumarnenavath.payments.domain

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, String> {
    fun findByPspReference(pspReference: String): Payment?
    fun findByStatusOrderByCreatedAtDesc(status: PaymentStatus, pageable: Pageable): List<Payment>
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<Payment>
}

interface RefundRepository : JpaRepository<Refund, String> {
    fun findByPaymentIdOrderByCreatedAt(paymentId: String): List<Refund>
    fun findByPaymentIdIn(paymentIds: Collection<String>): List<Refund>
}

interface DisputeRepository : JpaRepository<Dispute, String> {
    fun findByPspDisputeId(pspDisputeId: String): Dispute?
    fun findByPaymentIdOrderByCreatedAt(paymentId: String): List<Dispute>
    fun findByPaymentIdIn(paymentIds: Collection<String>): List<Dispute>
}
