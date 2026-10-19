# ADR 0002: Idempotency keys on unsafe POSTs

**Status:** Accepted

## Context

Clients retry on timeouts. Without protection, a retried "create payment" or "refund" can charge or refund
a customer twice. That is the most expensive class of payments bug.

## Decision

`POST /api/v2/payments` and `POST /api/v2/payments/{id}/refunds` require an `Idempotency-Key` header.

- The key is scoped to the endpoint (and to the payment, for refunds) and claimed with an **INSERT** keyed by
  `sha256(scope + key)`. The primary key resolves concurrent retries, with no read-then-write race.
- A SHA-256 of the request body is stored with the key:
  - Same key and same body after completion: the stored status and body are replayed, with `Idempotent-Replayed: true`.
  - Same key and a different body: `422 idempotency_key_reused`.
  - Same key while the first request is still running: `409 request_in_progress`.
- **4xx outcomes are stored**, since they are deterministic and retrying won't change them. **5xx and unexpected
  errors release the key**, so a retry after a PSP outage really retries.
- The same key is forwarded to the PSP, so the gateway also de-duplicates if we time out after it charged.
- Keys expire after 24 hours (`payments.idempotency.retention`).

## Consequences

- Retries are safe end to end: client, this service and the PSP.
- One extra table write per unsafe request.
- The v1 API never had idempotency keys. That is one more reason to migrate consumers to v2.
