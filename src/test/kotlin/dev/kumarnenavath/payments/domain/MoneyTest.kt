package dev.kumarnenavath.payments.domain

import dev.kumarnenavath.payments.common.ApiException
import dev.kumarnenavath.payments.common.InvalidAmountException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyTest {

    @ParameterizedTest(name = "{0} {1} -> {2} minor units")
    @CsvSource(
        "12.34, USD, 1234",
        "12.3, USD, 1230",
        "0.01, EUR, 1",
        "1500, JPY, 1500",
        "99.990, INR, 9999",
    )
    fun `converts decimal amounts to minor units using the currency's exponent`(amount: String, currency: String, expected: Long) {
        assertThat(Money.toMinor(BigDecimal(amount), currency)).isEqualTo(expected)
    }

    @Test
    fun `rejects more decimals than the currency allows instead of rounding`() {
        assertThatThrownBy { Money.toMinor(BigDecimal("12.345"), "USD") }
            .isInstanceOf(InvalidAmountException::class.java)
            .hasMessageContaining("more than 2 decimal places")
        assertThatThrownBy { Money.toMinor(BigDecimal("10.5"), "JPY") }
            .isInstanceOf(InvalidAmountException::class.java)
    }

    @Test
    fun `formats minor units back to a decimal amount`() {
        assertThat(Money.toMajor(1234, "USD").toPlainString()).isEqualTo("12.34")
        assertThat(Money.toMajor(5, "USD").toPlainString()).isEqualTo("0.05")
        assertThat(Money.toMajor(1500, "JPY").toPlainString()).isEqualTo("1500")
    }

    @Test
    fun `rejects unknown currencies`() {
        assertThatThrownBy { Money.currency("XYZ1") }
            .isInstanceOf(ApiException::class.java)
            .extracting("code").isEqualTo("invalid_currency")
    }
}
