package dev.kumarnenavath.payments.psp

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class RetryTest {
    private val sleeps = mutableListOf<Duration>()
    private val retry = Retry(maxAttempts = 3, backoff = Duration.ofMillis(100)) { sleeps += it }

    @Test
    fun `retries transient PSP failures with exponential backoff`() {
        var calls = 0
        val result = retry.call("authorize") {
            calls++
            if (calls < 3) throw PspTransientException("timeout")
            "ok"
        }

        assertThat(result).isEqualTo("ok")
        assertThat(calls).isEqualTo(3)
        assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200))
    }

    @Test
    fun `gives up after max attempts with a 503 psp_unavailable error`() {
        var calls = 0
        assertThatThrownBy { retry.call("refund") { calls++; throw PspTransientException("timeout") } }
            .isInstanceOf(PspUnavailableException::class.java)
            .extracting("code").isEqualTo("psp_unavailable")
        assertThat(calls).isEqualTo(3)
    }

    @Test
    fun `does not retry non-transient errors`() {
        var calls = 0
        assertThatThrownBy { retry.call("capture") { calls++; throw IllegalStateException("bug") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(calls).isEqualTo(1)
        assertThat(sleeps).isEmpty()
    }
}
