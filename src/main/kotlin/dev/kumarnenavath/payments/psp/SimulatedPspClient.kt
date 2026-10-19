package dev.kumarnenavath.payments.psp

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic stand-in for a real gateway, driven by the last two digits of the amount
 * (the same trick gateway sandboxes use with magic card numbers):
 *
 * - `..02` -> declined with `card_declined`
 * - `..05` -> declined with `insufficient_funds`
 * - `..99` -> PSP times out on every call (exercises retries and the 503 path)
 * - anything else -> approved
 *
 * Authorizations are de-duplicated by idempotency key, like a real PSP.
 */
@Component
class SimulatedPspClient : PspClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val authorizations = ConcurrentHashMap<String, AuthorizationResult>()

    override fun authorize(request: AuthorizationRequest): AuthorizationResult {
        failIfOutage(request.amountMinor, "authorize")
        return authorizations.computeIfAbsent(request.idempotencyKey) {
            when (request.amountMinor % 100) {
                2L -> AuthorizationResult.Declined("card_declined")
                5L -> AuthorizationResult.Declined("insufficient_funds")
                else -> AuthorizationResult.Approved("psp_ch_" + shortId())
            }
        }.also { log.info("psp authorize payment={} result={}", request.paymentId, it) }
    }

    override fun capture(pspReference: String, amountMinor: Long, currency: String) {
        failIfOutage(amountMinor, "capture")
    }

    override fun void(pspReference: String) = Unit

    override fun refund(request: RefundRequest): String {
        failIfOutage(request.amountMinor, "refund")
        return "psp_re_" + shortId()
    }

    private fun failIfOutage(amountMinor: Long, operation: String) {
        if (amountMinor % 100 == 99L) throw PspTransientException("simulated PSP timeout during $operation")
    }

    private fun shortId() = UUID.randomUUID().toString().replace("-", "").take(20)
}
