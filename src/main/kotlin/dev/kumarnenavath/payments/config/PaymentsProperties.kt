package dev.kumarnenavath.payments.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("payments")
data class PaymentsProperties(
    val psp: Psp = Psp(),
    val webhook: Webhook = Webhook(),
    val outbox: Outbox = Outbox(),
    val idempotency: Idempotency = Idempotency(),
) {
    data class Psp(val retry: RetrySettings = RetrySettings())

    data class RetrySettings(val maxAttempts: Int = 3, val backoff: Duration = Duration.ofMillis(200))

    /** Several secrets are accepted at once so the signing secret can be rotated without dropping webhooks. */
    data class Webhook(val secrets: List<String> = emptyList(), val tolerance: Duration = Duration.ofMinutes(5))

    data class Outbox(val topic: String = "payment-events", val relay: Relay = Relay())

    data class Relay(val enabled: Boolean = false, val pollIntervalMs: Long = 500, val batchSize: Int = 100)

    data class Idempotency(val retention: Duration = Duration.ofHours(24))
}
