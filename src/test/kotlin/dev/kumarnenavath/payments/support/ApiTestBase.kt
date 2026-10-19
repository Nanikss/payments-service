package dev.kumarnenavath.payments.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.kumarnenavath.payments.webhook.WebhookSignatureVerifier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
abstract class ApiTestBase {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    fun json(value: Any): String = objectMapper.writeValueAsString(value)

    fun postPayment(
        body: Map<String, Any?>,
        idempotencyKey: String? = UUID.randomUUID().toString(),
    ): ResultActionsDsl = mockMvc.post("/api/v2/payments") {
        contentType = MediaType.APPLICATION_JSON
        idempotencyKey?.let { header("Idempotency-Key", it) }
        content = json(body)
    }

    fun createPayment(amountMinor: Long = 10_000, capture: Boolean = true, currency: String = "USD"): JsonNode {
        val result = postPayment(
            mapOf("amountMinor" to amountMinor, "currency" to currency, "merchantReference" to "order-" + UUID.randomUUID(), "capture" to capture),
        ).andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    fun postRefund(paymentId: String, amountMinor: Long, idempotencyKey: String = UUID.randomUUID().toString()): ResultActionsDsl =
        mockMvc.post("/api/v2/payments/$paymentId/refunds") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = json(mapOf("amountMinor" to amountMinor, "reason" to "requested_by_customer"))
        }

    fun createRefund(paymentId: String, amountMinor: Long): JsonNode {
        val result = postRefund(paymentId, amountMinor).andExpect { status { isCreated() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    fun getPayment(paymentId: String): JsonNode {
        val result = mockMvc.get("/api/v2/payments/$paymentId").andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    fun sendWebhook(
        type: String,
        data: Map<String, Any?>,
        eventId: String = "evt_psp_" + UUID.randomUUID(),
        secret: String = "whsec_test_current",
        timestamp: Long = Instant.now().epochSecond,
    ): ResultActionsDsl {
        val payload = json(mapOf("id" to eventId, "type" to type, "created" to timestamp, "data" to data))
        return mockMvc.post("/webhooks/psp") {
            contentType = MediaType.APPLICATION_JSON
            header("Psp-Signature", "t=$timestamp,v1=${WebhookSignatureVerifier.sign(secret, timestamp, payload)}")
            content = payload
        }
    }
}
