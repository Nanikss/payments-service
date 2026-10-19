package dev.kumarnenavath.payments.graphql

import dev.kumarnenavath.payments.support.ApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.graphql.tester.AutoConfigureGraphQlTester
import org.springframework.graphql.test.tester.GraphQlTester

@AutoConfigureGraphQlTester
class PaymentGraphQlTest : ApiTestBase() {

    @Autowired
    lateinit var graphQlTester: GraphQlTester

    @Test
    fun `queries a payment with its refunds`() {
        val id = createPayment(amountMinor = 20_000)["id"].asText()
        createRefund(id, 5_000)

        val response = graphQlTester.document(
            """
            query(${'$'}id: ID!) {
              payment(id: ${'$'}id) {
                id status amountMinor currency refundableMinor
                refunds { amountMinor status }
                disputes { id }
              }
            }
            """,
        ).variable("id", id).execute()

        response.path("payment.status").entity(String::class.java).isEqualTo("CAPTURED")
        response.path("payment.amountMinor").entity(Long::class.javaObjectType).isEqualTo(20_000L)
        response.path("payment.refundableMinor").entity(Long::class.javaObjectType).isEqualTo(15_000L)
        response.path("payment.refunds[0].status").entity(String::class.java).isEqualTo("PENDING")
        response.path("payment.disputes").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `lists payments by status with batched refunds`() {
        val id = createPayment(amountMinor = 3_300, capture = false)["id"].asText()

        val ids = graphQlTester.document("""{ payments(status: AUTHORIZED, limit: 100) { id status refunds { id } } }""")
            .execute()
            .path("payments[*].id").entityList(String::class.java).get()

        assertThat(ids).contains(id)
    }

    @Test
    fun `an unknown id resolves to null`() {
        graphQlTester.document("""{ payment(id: "pay_nope") { id } }""")
            .execute()
            .path("payment").valueIsNull()
    }
}
