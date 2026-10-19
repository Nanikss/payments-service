package dev.kumarnenavath.payments.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

enum class RefundStatus { PENDING, SUCCEEDED, FAILED }

@Entity
@Table(name = "refunds")
class Refund(
    @Id
    val id: String,
    @Column(name = "payment_id")
    val paymentId: String,
    @Column(name = "amount_minor")
    val amountMinor: Long,
    val reason: String?,
    @Enumerated(EnumType.STRING)
    var status: RefundStatus = RefundStatus.PENDING,
    @Column(name = "psp_reference")
    var pspReference: String? = null,
    @Column(name = "created_at")
    val createdAt: Instant,
    @Column(name = "updated_at")
    var updatedAt: Instant = createdAt,
) {
    @Version
    var version: Long? = null
}

enum class DisputeStatus { OPEN, WON, LOST }

@Entity
@Table(name = "disputes")
class Dispute(
    @Id
    val id: String,
    @Column(name = "payment_id")
    val paymentId: String,
    @Column(name = "psp_dispute_id")
    val pspDisputeId: String,
    @Column(name = "amount_minor")
    val amountMinor: Long,
    val reason: String?,
    @Enumerated(EnumType.STRING)
    var status: DisputeStatus = DisputeStatus.OPEN,
    @Column(name = "created_at")
    val createdAt: Instant,
    @Column(name = "updated_at")
    var updatedAt: Instant = createdAt,
) {
    @Version
    var version: Long? = null
}
