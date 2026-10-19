package dev.kumarnenavath.payments.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Generated spec at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`. */
@Configuration
class OpenApiConfig {
    @Bean
    fun paymentsOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Payments Service API")
            .version("v2")
            .description(
                "Card payments: authorize, capture, void, refunds and disputes. Amounts are integer minor units. " +
                    "Unsafe POSTs require an Idempotency-Key header. /api/v1 is deprecated (sunset 2027-03-31).",
            ),
    )
}
