package dev.kumarnenavath.payments.api

import dev.kumarnenavath.payments.support.ApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

class LegacyApiTest : ApiTestBase() {

    private fun postCharge(amount: String, currency: String = "USD") = mockMvc.post("/api/v1/charges") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"amount": $amount, "currency": "$currency", "reference": "legacy-order"}"""
    }

    @Test
    fun `v1 keeps its decimal amount and lowercase status shape`() {
        postCharge("19.95").andExpect {
            status { isCreated() }
            jsonPath("$.amount") { value("19.95") }
            jsonPath("$.status") { value("succeeded") }
            jsonPath("$.currency") { value("USD") }
            jsonPath("$.reference") { value("legacy-order") }
        }
    }

    @Test
    fun `v1 responses announce deprecation, sunset and the successor API`() {
        postCharge("5.00").andExpect {
            header { string("Deprecation", "@1788220800") }
            header { string("Sunset", "Wed, 31 Mar 2027 00:00:00 GMT") }
            header { string("Link", "</api/v2/payments>; rel=\"successor-version\"") }
        }
    }

    @Test
    fun `a v1 charge is the same payment in v2, stored in minor units`() {
        val result = postCharge("12.34").andReturn()
        val id = objectMapper.readTree(result.response.contentAsString)["id"].asText()

        val v2 = getPayment(id)
        assertThat(v2["amountMinor"].asLong()).isEqualTo(1_234)
        assertThat(v2["status"].asText()).isEqualTo("CAPTURED")

        mockMvc.get("/api/v1/charges/$id").andExpect { jsonPath("$.amount") { value("12.34") } }
    }

    @Test
    fun `v1 rejects amounts with too many decimals instead of rounding money`() {
        postCharge("10.005").andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("invalid_amount") }
        }
    }
}
