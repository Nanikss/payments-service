package dev.kumarnenavath.payments.psp

import dev.kumarnenavath.payments.common.ApiException
import org.springframework.http.HttpStatus
import java.time.Duration

/**
 * Port to the payment service provider (Stripe/Adyen-style gateway). The service only depends on this
 * interface; [SimulatedPspClient] stands in for the real gateway locally and in tests.
 */
interface PspClient {
    fun authorize(request: AuthorizationRequest): AuthorizationResult
    fun capture(pspReference: String, amountMinor: Long, currency: String)
    fun void(pspReference: String)

    /** Starts a refund and returns the PSP's refund reference. The outcome arrives later by webhook. */
    fun refund(request: RefundRequest): String
}

data class AuthorizationRequest(
    val paymentId: String,
    val amountMinor: Long,
    val currency: String,
    /** Forwarded so the PSP also de-duplicates if we retry after a timeout. */
    val idempotencyKey: String,
)

data class RefundRequest(
    val refundId: String,
    val pspReference: String,
    val amountMinor: Long,
    val currency: String,
    val idempotencyKey: String,
)

sealed interface AuthorizationResult {
    data class Approved(val pspReference: String) : AuthorizationResult
    data class Declined(val code: String) : AuthorizationResult
}

/** Timeouts, 5xx and connection resets from the PSP: safe to retry because every call carries an idempotency key. */
class PspTransientException(message: String) : RuntimeException(message)

class PspUnavailableException(operation: String, cause: Throwable) :
    ApiException(HttpStatus.SERVICE_UNAVAILABLE, "psp_unavailable", "Payment provider unavailable during $operation; retry with the same Idempotency-Key", cause)

/** Bounded retry with exponential backoff, applied only to [PspTransientException]. */
class Retry(
    private val maxAttempts: Int,
    private val backoff: Duration,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    fun <T> call(operation: String, block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: PspTransientException) {
                if (attempt >= maxAttempts) throw PspUnavailableException(operation, e)
                sleeper(backoff.multipliedBy(1L shl (attempt - 1)))
                attempt++
            }
        }
    }
}
