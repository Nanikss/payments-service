package dev.kumarnenavath.payments.webhook

import dev.kumarnenavath.payments.config.PaymentsProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class WebhookSignatureVerifierTest {
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val properties = PaymentsProperties(
        webhook = PaymentsProperties.Webhook(secrets = listOf("whsec_new", "whsec_old"), tolerance = Duration.ofMinutes(5)),
    )
    private val verifier = WebhookSignatureVerifier(properties, Clock.fixed(now, ZoneOffset.UTC))
    private val payload = """{"id":"evt_1","type":"refund.succeeded","data":{"refundId":"re_1"}}"""

    private fun header(secret: String = "whsec_new", ts: Long = now.epochSecond, body: String = payload) =
        "t=$ts,v1=${WebhookSignatureVerifier.sign(secret, ts, body)}"

    @Test
    fun `accepts a valid signature`() {
        assertThatCode { verifier.verify(payload, header()) }.doesNotThrowAnyException()
    }

    @Test
    fun `accepts the previous secret during rotation`() {
        assertThatCode { verifier.verify(payload, header(secret = "whsec_old")) }.doesNotThrowAnyException()
    }

    @Test
    fun `accepts a header with several v1 signatures if one matches`() {
        val ts = now.epochSecond
        val multi = "t=$ts,v1=deadbeef,v1=${WebhookSignatureVerifier.sign("whsec_new", ts, payload)}"
        assertThatCode { verifier.verify(payload, multi) }.doesNotThrowAnyException()
    }

    @Test
    fun `rejects a tampered body`() {
        val tampered = payload.replace("re_1", "re_2")
        assertThatThrownBy { verifier.verify(tampered, header()) }.hasMessageContaining("does not match")
    }

    @Test
    fun `rejects an unknown secret`() {
        assertThatThrownBy { verifier.verify(payload, header(secret = "whsec_attacker")) }.hasMessageContaining("does not match")
    }

    @Test
    fun `rejects replayed requests with an old timestamp`() {
        val old = now.minus(Duration.ofMinutes(6)).epochSecond
        assertThatThrownBy { verifier.verify(payload, header(ts = old)) }.hasMessageContaining("tolerance")
    }

    @Test
    fun `rejects missing or malformed headers`() {
        assertThatThrownBy { verifier.verify(payload, null) }.hasMessageContaining("Missing")
        assertThatThrownBy { verifier.verify(payload, "v1=abc") }.hasMessageContaining("timestamp")
        assertThatThrownBy { verifier.verify(payload, "t=${now.epochSecond}") }.hasMessageContaining("no v1")
    }
}
