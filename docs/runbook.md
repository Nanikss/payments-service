# Payments Service Runbook

On-call guide for the payments service. Each section covers the symptom, how to confirm it, and what to do.

**Dashboards and metrics** (`/actuator/metrics`):

| Metric | Meaning |
|---|---|
| `payments.created{outcome}` | Authorizations by outcome: `authorized`, `failed`, `psp_unavailable` |
| `payments.webhooks{type,outcome}` | Webhooks by type and outcome: `applied`, `ignored`, `duplicate` |
| `payments.api.v1.requests{endpoint}` | Remaining traffic on the deprecated v1 API |
| `http.server.requests{uri,status}` | Latency and error rate per endpoint |

Health: `/actuator/health/liveness`, `/actuator/health/readiness`.

---

## 1. Spike in `psp_unavailable` (503s on create or refund)

**Symptom:** `payments.created{outcome=psp_unavailable}` rising, and clients receive 503 `psp_unavailable`.

**Confirm:** Check the PSP status page. In the logs, look for `simulated PSP timeout` or real gateway timeouts. Each call is retried 3 times with exponential backoff (`payments.psp.retry.*`) before it gives up.

**Impact:** Payments created during the outage are marked `FAILED` with `failure_code = psp_unavailable`. Their Idempotency-Key is **released**, so client retries with the same key are safe and reach the PSP again. The PSP de-duplicates on the same key.

**Action:**
1. Don't raise retry counts during an outage. That amplifies load on a struggling gateway.
2. Once the PSP recovers, clients retry on their own. Nothing needs replaying on our side.
3. If a refund failed this way, its reservation was released (the refund is `FAILED`), so support can re-issue it.

## 2. Webhooks rejected with `invalid_signature`

**Symptom:** The PSP dashboard shows failing deliveries, and our logs show 400s with `invalid_signature`.

**Likely causes:**
- **The secret was rotated on the PSP side only.** Add the new secret to `WEBHOOK_SECRETS` (comma-separated, new secret first) and redeploy. Both secrets are accepted until you remove the old one.
- **Clock skew greater than 5 minutes.** "Outside the allowed tolerance" in the logs means the host clock is off. Fix NTP rather than raising the tolerance.
- **A proxy rewriting the body.** The HMAC covers the raw bytes, so anything that re-encodes JSON in front of the service breaks it.

**After the fix:** Replay failed deliveries from the PSP dashboard. Duplicates are safe because each event id is processed once (`webhook_events` table).

## 3. Outbox backlog (downstream consumers lagging)

**Symptom:** Ledger or notifications are behind, and unpublished rows are growing:

```sql
SELECT COUNT(*), MIN(created_at) FROM outbox_events WHERE published_at IS NULL;
SELECT id, aggregate_id, event_type, attempts FROM outbox_events
 WHERE published_at IS NULL ORDER BY created_at LIMIT 20;
```

**Notes:** The relay stops at the first failed send to preserve per-payment ordering, so one bad event blocks the queue behind it. `attempts` shows which event that is.

**Action:**
1. Kafka down or unreachable: restore the broker. The relay resumes on its own and publishes in order.
2. A single poison event (`attempts` keeps climbing): inspect the payload. Fix the cause before touching the row. Never delete outbox rows to "unblock" the queue, because consumers would miss a state change.
3. Delivery is at least once. Consumers must de-duplicate on `eventId`.

## 4. Refund stuck in `PENDING`

**Symptom:** The customer says the refund never arrived, and the refund row is `PENDING` for longer than the PSP's SLA.

**Confirm:**

```sql
SELECT r.id, r.status, r.psp_reference, r.created_at, p.status AS payment_status
  FROM refunds r JOIN payments p ON p.id = r.payment_id
 WHERE r.status = 'PENDING' AND r.created_at < NOW() - INTERVAL 2 DAY;
```

**Action:**
1. Look up `psp_reference` in the PSP dashboard.
2. If the PSP shows it settled, the webhook was missed. Replay `refund.succeeded` / `refund.failed` from the PSP. Our handling is idempotent.
3. While it's pending, the amount stays **reserved** (`pending_refund_minor`), so it can't be refunded twice. Don't issue a manual second refund.

## 5. Dispute opened

**Symptom:** A `dispute.opened` webhook moves the payment to `DISPUTED` and publishes a `dispute.opened` event.

**Behaviour:** Refunds are blocked while a dispute is open, because refunding a disputed charge risks paying out twice. `dispute.closed` with `won` restores the settled status. `lost` moves the payment to `CHARGED_BACK`.

**Action:** Finance or ops submits evidence in the PSP dashboard before the deadline. The service needs no manual step. Events can arrive out of order (closed before opened), and both orders are handled.

## 6. 409 `concurrent_modification`

**Meaning:** Two requests changed the same payment at once, for example two refunds, and optimistic locking (`@Version`) rejected the stale one. This is protection, not a failure.

**Action:** None on the server. Clients should retry with the **same** Idempotency-Key. A sustained rate points to a client sending parallel duplicates.

## 7. v1 sunset checklist (sunset 2027-03-31)

1. Watch `payments.api.v1.requests` and identify the remaining callers in access logs.
2. Every v1 response already carries `Deprecation`, `Sunset` and `Link: successor-version` headers.
3. Two weeks before sunset, move v1 to `410 Gone` behind a flag, then remove `LegacyChargeController` and its contract test.
