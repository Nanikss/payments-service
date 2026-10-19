package dev.kumarnenavath.payments.idempotency

import com.fasterxml.jackson.databind.ObjectMapper
import dev.kumarnenavath.payments.common.ApiException
import dev.kumarnenavath.payments.common.Problems
import dev.kumarnenavath.payments.config.PaymentsProperties
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

@Entity
@Table(name = "idempotency_keys")
class IdempotencyRecord(
    @Id
    val id: String,
    val scope: String,
    @Column(name = "idempotency_key")
    val idempotencyKey: String,
    @Column(name = "request_hash")
    val requestHash: String,
    @Column(name = "created_at")
    val createdAt: Instant,
    @Column(name = "response_status")
    var responseStatus: Int? = null,
    @Column(name = "response_body", columnDefinition = "TEXT")
    var responseBody: String? = null,
) {
    @Version
    var version: Long? = null
}

interface IdempotencyRepository : JpaRepository<IdempotencyRecord, String> {
    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :cutoff")
    fun deleteCreatedBefore(cutoff: Instant): Int
}

/**
 * Stripe-style `Idempotency-Key` handling for unsafe POSTs.
 *
 * 1. Same key + same body after completion -> the stored response is replayed (`Idempotent-Replayed: true`).
 * 2. Same key + different body -> 422, because the client has a bug.
 * 3. Same key while the first request is still running -> 409, so the client backs off.
 *
 * The key is claimed with an INSERT, so the primary key resolves races between concurrent retries.
 * 4xx outcomes are stored, since retrying won't change them. 5xx and unexpected errors release the key so the
 * client can retry safely.
 */
@Component
class IdempotencyService(
    private val repository: IdempotencyRepository,
    private val tx: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun execute(scope: String, key: String, request: Any, action: () -> ResponseEntity<*>): ResponseEntity<*> {
        if (key.isBlank() || key.length > 255) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key", "Idempotency-Key must be 1-255 characters")
        }
        val id = sha256("$scope\n$key")
        val requestHash = sha256(objectMapper.writeValueAsString(request))

        repository.findById(id).orElse(null)?.let { return replay(it, requestHash) }
        try {
            tx.executeWithoutResult {
                repository.saveAndFlush(IdempotencyRecord(id, scope, key, requestHash, clock.instant()))
            }
        } catch (e: DataIntegrityViolationException) {
            return replay(repository.findById(id).orElseThrow(), requestHash)
        }

        val response = try {
            action()
        } catch (e: ApiException) {
            if (e.status.is4xxClientError) {
                complete(id, e.status.value(), Problems.of(e.status, e.code, e.message ?: e.status.reasonPhrase))
            } else {
                release(id)
            }
            throw e
        } catch (e: Exception) {
            release(id)
            throw e
        }
        complete(id, response.statusCode.value(), response.body)
        return response
    }

    private fun replay(record: IdempotencyRecord, requestHash: String): ResponseEntity<*> {
        if (record.requestHash != requestHash) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused", "Idempotency-Key was already used with a different request body")
        }
        val status = record.responseStatus
            ?: throw ApiException(HttpStatus.CONFLICT, "request_in_progress", "A request with this Idempotency-Key is still being processed")
        return ResponseEntity.status(status)
            .header("Idempotent-Replayed", "true")
            .contentType(if (status >= 400) MediaType.APPLICATION_PROBLEM_JSON else MediaType.APPLICATION_JSON)
            .body(objectMapper.readTree(record.responseBody ?: "null"))
    }

    private fun complete(id: String, status: Int, body: Any?) {
        tx.executeWithoutResult {
            val record = repository.findById(id).orElseThrow()
            record.responseStatus = status
            record.responseBody = objectMapper.writeValueAsString(body)
        }
    }

    private fun release(id: String) {
        tx.executeWithoutResult { repository.deleteById(id) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

@Component
class IdempotencyCleanup(
    private val repository: IdempotencyRepository,
    private val properties: PaymentsProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @Transactional
    fun purgeExpired() {
        val removed = repository.deleteCreatedBefore(clock.instant().minus(properties.idempotency.retention))
        if (removed > 0) log.info("purged {} expired idempotency keys", removed)
    }
}
