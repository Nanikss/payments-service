package dev.kumarnenavath.payments.contract

import com.fasterxml.jackson.databind.JsonNode
import dev.kumarnenavath.payments.support.ApiTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.util.UUID

/**
 * Provider-side contract tests. Each file in `src/test/resources/contracts` is what a downstream consumer
 * relies on: the request it sends and the fields, types and values it reads back. A breaking change to the
 * API shape (a renamed field, a changed type, a different status code) fails here before it reaches a consumer.
 *
 * Expected values are either a literal, matched exactly, or a type matcher: `type:string`, `type:number`,
 * `type:boolean`, `type:array`, `type:object`, `type:nullable`. Fields that aren't listed may change freely.
 */
class ContractTest : ApiTestBase() {

    @TestFactory
    fun contracts(): List<DynamicTest> =
        PathMatchingResourcePatternResolver().getResources("classpath:contracts/*.json").sortedBy { it.filename }.map { resource ->
            val contract = objectMapper.readTree(resource.inputStream)
            DynamicTest.dynamicTest("${resource.filename}: ${contract["description"].asText()}") { verify(contract) }
        }

    private fun verify(contract: JsonNode) {
        val req = contract["request"]
        val builder = request(HttpMethod.valueOf(req["method"].asText()), req["path"].asText())
            .contentType(MediaType.APPLICATION_JSON)
        req["headers"]?.fields()?.forEach { (name, value) -> builder.header(name, value.asText().replace("{{uuid}}", UUID.randomUUID().toString())) }
        req["body"]?.let { builder.content(objectMapper.writeValueAsString(it)) }

        val response = mockMvc.perform(builder).andReturn().response
        val expected = contract["response"]

        assertThat(response.status).describedAs("status").isEqualTo(expected["status"].asInt())
        expected["headers"]?.fields()?.forEach { (name, value) ->
            assertThat(response.getHeader(name)).describedAs("header $name").isNotNull().contains(value.asText())
        }
        expected["body"]?.let { matches(it, objectMapper.readTree(response.contentAsString), "$") }
    }

    private fun matches(expected: JsonNode, actual: JsonNode?, path: String) {
        assertThat(actual).describedAs("$path is missing").isNotNull
        actual!!
        if (expected.isObject) {
            expected.fields().forEach { (field, value) -> matches(value, actual[field], "$path.$field") }
            return
        }
        val text = expected.asText()
        when {
            expected.isTextual && text.startsWith("type:") -> {
                val ok = when (text.removePrefix("type:")) {
                    "string" -> actual.isTextual
                    "number" -> actual.isNumber
                    "boolean" -> actual.isBoolean
                    "array" -> actual.isArray
                    "object" -> actual.isObject
                    "nullable" -> true
                    else -> error("Unknown matcher $text at $path")
                }
                assertThat(ok).describedAs("$path should be $text but was $actual").isTrue()
            }
            else -> assertThat(actual).describedAs(path).isEqualTo(expected)
        }
    }
}
