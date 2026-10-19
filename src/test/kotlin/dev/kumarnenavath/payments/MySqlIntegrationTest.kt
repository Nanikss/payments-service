package dev.kumarnenavath.payments

import dev.kumarnenavath.payments.support.ApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Runs the Flyway migrations and a full payment, refund and webhook flow against real MySQL 8.4.
 * Skipped automatically where Docker isn't available; CI (GitLab and GitHub) always runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class MySqlIntegrationTest : ApiTestBase() {

    companion object {
        @Container
        @ServiceConnection
        @JvmField
        val mysql: MySQLContainer<Nothing> = MySQLContainer<Nothing>(DockerImageName.parse("mysql:8.4"))
    }

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `migrations apply cleanly on MySQL`() {
        val product = jdbc.queryForObject("select version()", String::class.java)
        assertThat(product).startsWith("8.4")
        val applied = jdbc.queryForObject("select count(*) from flyway_schema_history where success = 1", Int::class.javaObjectType)
        assertThat(applied).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `full payment lifecycle on MySQL`() {
        val id = createPayment(amountMinor = 25_000)["id"].asText()
        val refundId = createRefund(id, 25_000)["id"].asText()
        sendWebhook("refund.succeeded", mapOf("refundId" to refundId))

        val payment = getPayment(id)
        assertThat(payment["status"].asText()).isEqualTo("REFUNDED")
        assertThat(payment["refundedMinor"].asLong()).isEqualTo(25_000)
    }
}
