package dev.kumarnenavath.payments.api

import dev.kumarnenavath.payments.common.ApiException
import dev.kumarnenavath.payments.common.Problems
import dev.kumarnenavath.payments.common.toResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

@RestControllerAdvice
class ErrorHandler {

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ProblemDetail> =
        Problems.of(e.status, e.code, e.message ?: e.status.reasonPhrase).toResponse()

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> =
        Problems.of(HttpStatus.BAD_REQUEST, "validation_failed", "Request validation failed").apply {
            setProperty("errors", e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") })
        }.toResponse()

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(e: MissingRequestHeaderException): ResponseEntity<ProblemDetail> =
        Problems.of(HttpStatus.BAD_REQUEST, "missing_header", "Required header '${e.headerName}' is missing").toResponse()

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ProblemDetail> =
        Problems.of(HttpStatus.BAD_REQUEST, "malformed_request", "Request body is missing or malformed").toResponse()

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ProblemDetail> =
        Problems.of(HttpStatus.BAD_REQUEST, "invalid_parameter", "Invalid value for '${e.name}'").toResponse()

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleConcurrentUpdate(e: ObjectOptimisticLockingFailureException): ResponseEntity<ProblemDetail> =
        Problems.of(HttpStatus.CONFLICT, "concurrent_modification", "The payment was modified concurrently; retry the request").toResponse()
}
