package dev.kumarnenavath.payments.domain

import dev.kumarnenavath.payments.common.ApiException
import dev.kumarnenavath.payments.common.InvalidAmountException
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.util.Currency

/**
 * Money is stored and moved as a Long count of minor units (cents, paise, yen) plus an ISO-4217 code.
 * Decimal amounts only exist at the edge, for the legacy v1 API.
 */
object Money {
    fun currency(code: String): Currency =
        runCatching { Currency.getInstance(code.uppercase()) }
            .getOrElse { throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_currency", "Unsupported currency '$code'") }

    /** `12.34 USD` -> `1234`. Rejects amounts with more decimals than the currency allows instead of rounding. */
    fun toMinor(amount: BigDecimal, currencyCode: String): Long {
        val digits = fractionDigits(currencyCode)
        return try {
            amount.movePointRight(digits).longValueExact()
        } catch (e: ArithmeticException) {
            throw InvalidAmountException("Amount $amount has more than $digits decimal places for ${currencyCode.uppercase()}")
        }
    }

    fun toMajor(minor: Long, currencyCode: String): BigDecimal = BigDecimal.valueOf(minor, fractionDigits(currencyCode))

    private fun fractionDigits(currencyCode: String) = currency(currencyCode).defaultFractionDigits.coerceAtLeast(0)
}
