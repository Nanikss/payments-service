package dev.kumarnenavath.payments.api

import dev.kumarnenavath.payments.domain.PaymentRepository
import dev.kumarnenavath.payments.outbox.OutboxRepository
import dev.kumarnenavath.payments.support.ApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

class PaymentApiTest : ApiTestBase() {

    @Autowired
    lateinit var paymentRepository: PaymentRepository

    @Autowired
    lateinit var outboxRepository: OutboxRepository

    @Test
    fun `creates and captures a payment in one call`() {
        val payment = createPayment(amountMinor = 2_500)

        assertThat(payment["status"].asText()).isEqualTo("CAPTURED")
        assertThat(payment["capturedMinor"].asLong()).isEqualTo(2_500)
        assertThat(payment["refundableMinor"].asLong()).isEqualTo(2_500)
        assertThat(payment["id"].asText()).startsWith("pay_")
        assertThat(payment["pspReference"].asText()).startsWith("psp_ch_")
    }

    @Test
    fun `authorizes only, then captures later`() {
        val payment = createPayment(amountMinor = 4_000, capture = false)
        assertThat(payment["status"].asText()).isEqualTo("AUTHORIZED")
        assertThat(payment["capturedMinor"].asLong()).isZero()

        mockMvc.post("/api/v2/payments/${payment["id"].asText()}/capture")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("CAPTURED") }
                jsonPath("$.capturedMinor") { value(4_000) }
            }
    }

    @Test
    fun `voids an authorization and rejects capturing it afterwards`() {
        val id = createPayment(amountMinor = 3_000, capture = false)["id"].asText()

        mockMvc.post("/api/v2/payments/$id/void").andExpect { jsonPath("$.status") { value("VOIDED") } }
        mockMvc.post("/api/v2/payments/$id/capture").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("invalid_state") }
        }
    }

    @Test
    fun `a declined card is recorded as FAILED with the decline code`() {
        val payment = createPayment(amountMinor = 1_002)
        assertThat(payment["status"].asText()).isEqualTo("FAILED")
        assertThat(payment["failureCode"].asText()).isEqualTo("card_declined")
    }

    @Test
    fun `replays the stored response when the client retries with the same key`() {
        val key = UUID.randomUUID().toString()
        val body = mapOf("amountMinor" to 7_700, "currency" to "USD", "merchantReference" to "order-retry", "capture" to true)

        val first = postPayment(body, key).andExpect { status { isCreated() } }.andReturn()
        val second = postPayment(body, key).andExpect {
            status { isCreated() }
            header { string("Idempotent-Replayed", "true") }
        }.andReturn()

        val firstId = objectMapper.readTree(first.response.contentAsString)["id"].asText()
        assertThat(objectMapper.readTree(second.response.contentAsString)["id"].asText()).isEqualTo(firstId)
        assertThat(paymentRepository.findAll().count { it.merchantReference == "order-retry" }).isEqualTo(1)
    }

    @Test
    fun `rejects reusing a key with a different body`() {
        val key = UUID.randomUUID().toString()
        postPayment(mapOf("amountMinor" to 100, "currency" to "USD", "merchantReference" to "a"), key)
            .andExpect { status { isCreated() } }
        postPayment(mapOf("amountMinor" to 200, "currency" to "USD", "merchantReference" to "a"), key)
            .andExpect {
                status { isUnprocessableEntity() }
                jsonPath("$.code") { value("idempotency_key_reused") }
            }
    }

    @Test
    fun `requires an Idempotency-Key on create`() {
        postPayment(mapOf("amountMinor" to 100, "currency" to "USD", "merchantReference" to "a"), idempotencyKey = null)
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("missing_header") }
            }
    }

    @Test
    fun `validates the request body`() {
        postPayment(mapOf("amountMinor" to -5, "currency" to "US", "merchantReference" to ""))
            .andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.code") { value("validation_failed") }
                jsonPath("$.errors.amountMinor") { exists() }
                jsonPath("$.errors.currency") { exists() }
                jsonPath("$.errors.merchantReference") { exists() }
            }
    }

    @Test
    fun `refunds reserve the amount and refuse to over-refund`() {
        val id = createPayment(amountMinor = 10_000)["id"].asText()

        val refund = createRefund(id, 6_000)
        assertThat(refund["status"].asText()).isEqualTo("PENDING")

        postRefund(id, 4_001).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("invalid_amount") }
        }

        val payment = getPayment(id)
        assertThat(payment["pendingRefundMinor"].asLong()).isEqualTo(6_000)
        assertThat(payment["refundableMinor"].asLong()).isEqualTo(4_000)
        assertThat(payment["refunds"]).hasSize(1)
    }

    @Test
    fun `a stored 4xx outcome is replayed for the same refund key`() {
        val id = createPayment(amountMinor = 1_000)["id"].asText()
        val key = UUID.randomUUID().toString()

        postRefund(id, 5_000, key).andExpect { status { isUnprocessableEntity() } }
        postRefund(id, 5_000, key).andExpect {
            status { isUnprocessableEntity() }
            header { string("Idempotent-Replayed", "true") }
            jsonPath("$.code") { value("invalid_amount") }
        }
    }

    @Test
    fun `PSP outage returns 503 and releases the key so the client can retry`() {
        val key = UUID.randomUUID().toString()
        val body = mapOf("amountMinor" to 1_099, "currency" to "USD", "merchantReference" to "order-outage")

        postPayment(body, key).andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("psp_unavailable") }
        }
        // Not replayed: the key was released, so the retry reaches the PSP again.
        postPayment(body, key).andExpect {
            status { isServiceUnavailable() }
            header { doesNotExist("Idempotent-Replayed") }
        }
        val failed = paymentRepository.findAll().filter { it.merchantReference == "order-outage" }
        assertThat(failed).allSatisfy { assertThat(it.failureCode).isEqualTo("psp_unavailable") }
    }

    @Test
    fun `every state change writes an outbox event in the same transaction`() {
        val id = createPayment(amountMinor = 5_000)["id"].asText()
        createRefund(id, 1_000)

        val types = outboxRepository.findByAggregateIdOrderByCreatedAtAsc(id).map { it.eventType }
        assertThat(types).containsExactly("payment.authorized", "payment.captured", "refund.pending")
    }

    @Test
    fun `unknown payments are a 404 problem`() {
        mockMvc.get("/api/v2/payments/pay_missing").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("not_found") }
            jsonPath("$.title") { value("Not Found") }
        }
    }

    @Test
    fun `lists payments filtered by status`() {
        val authorizedId = createPayment(amountMinor = 6_100, capture = false)["id"].asText()

        mockMvc.get("/api/v2/payments?status=AUTHORIZED&limit=100").andExpect {
            status { isOk() }
            jsonPath("$[?(@.id == '$authorizedId')]") { exists() }
            jsonPath("$[?(@.status != 'AUTHORIZED')]") { doesNotExist() }
        }
    }
}
