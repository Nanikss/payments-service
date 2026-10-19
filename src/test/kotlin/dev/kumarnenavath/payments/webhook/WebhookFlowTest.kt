package dev.kumarnenavath.payments.webhook

import dev.kumarnenavath.payments.outbox.OutboxRepository
import dev.kumarnenavath.payments.support.ApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

class WebhookFlowTest : ApiTestBase() {

    @Autowired
    lateinit var outboxRepository: OutboxRepository

    @Test
    fun `refund webhooks settle refunds into PARTIALLY_REFUNDED then REFUNDED`() {
        val id = createPayment(amountMinor = 10_000)["id"].asText()

        val first = createRefund(id, 3_000)["id"].asText()
        sendWebhook("refund.succeeded", mapOf("refundId" to first)).andExpect { jsonPath("$.outcome") { value("applied") } }
        assertThat(getPayment(id)["status"].asText()).isEqualTo("PARTIALLY_REFUNDED")

        val second = createRefund(id, 7_000)["id"].asText()
        sendWebhook("refund.succeeded", mapOf("refundId" to second))

        val payment = getPayment(id)
        assertThat(payment["status"].asText()).isEqualTo("REFUNDED")
        assertThat(payment["refundedMinor"].asLong()).isEqualTo(10_000)
        assertThat(payment["refunds"].map { it["status"].asText() }).containsOnly("SUCCEEDED")
    }

    @Test
    fun `a failed refund webhook gives the amount back`() {
        val id = createPayment(amountMinor = 8_000)["id"].asText()
        val refundId = createRefund(id, 8_000)["id"].asText()
        assertThat(getPayment(id)["refundableMinor"].asLong()).isZero()

        sendWebhook("refund.failed", mapOf("refundId" to refundId))

        val payment = getPayment(id)
        assertThat(payment["status"].asText()).isEqualTo("CAPTURED")
        assertThat(payment["refundableMinor"].asLong()).isEqualTo(8_000)
        assertThat(payment["refunds"][0]["status"].asText()).isEqualTo("FAILED")
    }

    @Test
    fun `a redelivered event is acknowledged but applied only once`() {
        val id = createPayment(amountMinor = 9_000)["id"].asText()
        val refundId = createRefund(id, 1_000)["id"].asText()
        val eventId = "evt_psp_" + UUID.randomUUID()

        sendWebhook("refund.succeeded", mapOf("refundId" to refundId), eventId).andExpect { jsonPath("$.outcome") { value("applied") } }
        sendWebhook("refund.succeeded", mapOf("refundId" to refundId), eventId).andExpect {
            status { isOk() }
            jsonPath("$.outcome") { value("duplicate") }
        }

        assertThat(getPayment(id)["refundedMinor"].asLong()).isEqualTo(1_000)
        assertThat(outboxRepository.findByAggregateIdOrderByCreatedAtAsc(id).count { it.eventType == "refund.succeeded" }).isEqualTo(1)
    }

    @Test
    fun `a dispute that is lost becomes a chargeback`() {
        val payment = createPayment(amountMinor = 12_000)
        val id = payment["id"].asText()
        val dispute = mapOf(
            "disputeId" to "dp_psp_" + UUID.randomUUID(),
            "chargeReference" to payment["pspReference"].asText(),
            "amountMinor" to 12_000,
            "reason" to "fraudulent",
        )

        sendWebhook("dispute.opened", dispute)
        assertThat(getPayment(id)["status"].asText()).isEqualTo("DISPUTED")
        postRefund(id, 100).andExpect { status { isConflict() } }

        sendWebhook("dispute.closed", dispute + ("status" to "lost"))
        val after = getPayment(id)
        assertThat(after["status"].asText()).isEqualTo("CHARGED_BACK")
        assertThat(after["disputes"][0]["status"].asText()).isEqualTo("LOST")
    }

    @Test
    fun `dispute closed arriving before dispute opened is still handled`() {
        val payment = createPayment(amountMinor = 4_500)
        val id = payment["id"].asText()
        val dispute = mapOf(
            "disputeId" to "dp_psp_" + UUID.randomUUID(),
            "chargeReference" to payment["pspReference"].asText(),
            "amountMinor" to 4_500,
            "status" to "won",
        )

        sendWebhook("dispute.closed", dispute).andExpect { jsonPath("$.outcome") { value("applied") } }
        sendWebhook("dispute.opened", dispute - "status")

        val after = getPayment(id)
        assertThat(after["status"].asText()).isEqualTo("CAPTURED")
        assertThat(after["disputes"]).hasSize(1)
        assertThat(after["disputes"][0]["status"].asText()).isEqualTo("WON")
    }

    @Test
    fun `unknown event types and unknown refunds are acknowledged and ignored`() {
        sendWebhook("payout.paid", mapOf("payoutId" to "po_1")).andExpect { jsonPath("$.outcome") { value("ignored") } }
        sendWebhook("refund.succeeded", mapOf("refundId" to "re_unknown")).andExpect { jsonPath("$.outcome") { value("ignored") } }
    }

    @Test
    fun `accepts the previous signing secret during rotation`() {
        sendWebhook("payout.paid", emptyMap(), secret = "whsec_test_previous").andExpect { status { isOk() } }
    }

    @Test
    fun `rejects bad signatures, stale timestamps and malformed events`() {
        sendWebhook("payout.paid", emptyMap(), secret = "whsec_wrong").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_signature") }
        }
        sendWebhook("payout.paid", emptyMap(), timestamp = Instant.now().epochSecond - 3_600).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_signature") }
        }
        sendWebhook("refund.succeeded", mapOf("notTheRefundId" to "x")).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("invalid_event") }
        }
        mockMvc.post("/webhooks/psp") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"id":"evt_unsigned","type":"refund.succeeded","data":{}}"""
        }.andExpect { status { isBadRequest() } }
    }
}
