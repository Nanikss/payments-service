package dev.kumarnenavath.payments.api

import dev.kumarnenavath.payments.support.ApiTestBase
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class ApiDocsTest : ApiTestBase() {

    @Test
    fun `publishes an OpenAPI spec covering v2, v1 and webhooks`() {
        mockMvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.info.title") { value("Payments Service API") }
            jsonPath("$.paths['/api/v2/payments']") { exists() }
            jsonPath("$.paths['/api/v2/payments/{id}/refunds']") { exists() }
            jsonPath("$.paths['/api/v1/charges']") { exists() }
            jsonPath("$.paths['/webhooks/psp']") { exists() }
        }
    }
}
