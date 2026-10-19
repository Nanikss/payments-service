package dev.kumarnenavath.payments.common

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import java.util.UUID

/** Business error with a stable machine-readable [code]; rendered as RFC 7807 problem JSON. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class NotFoundException(what: String, id: String) :
    ApiException(HttpStatus.NOT_FOUND, "not_found", "$what $id not found")

class InvalidStateException(message: String) :
    ApiException(HttpStatus.CONFLICT, "invalid_state", message)

class InvalidAmountException(message: String) :
    ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_amount", message)

object Problems {
    fun of(status: HttpStatus, code: String, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            title = status.reasonPhrase
            setProperty("code", code)
        }
}

fun ProblemDetail.toResponse(): ResponseEntity<ProblemDetail> =
    ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(this)

object Ids {
    /** Prefixed, opaque ids such as `pay_3f9c...` so an id's type is obvious in logs and support tickets. */
    fun new(prefix: String): String = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(24)
}
