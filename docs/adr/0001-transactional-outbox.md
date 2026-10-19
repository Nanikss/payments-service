# ADR 0001: Publish payment events through a transactional outbox

**Status:** Accepted

## Context

Downstream services (ledger, notifications, analytics) need an event for every payment state change.
Writing to MySQL and then calling Kafka directly in the same request is a dual write. If the process dies
between the two, or Kafka is briefly down, the database and the event stream disagree. In payments that
means a ledger missing a refund, or a "captured" notification for a capture that rolled back.

## Decision

- Each state change inserts a row into `outbox_events` in the **same transaction** as the change
  (`OutboxWriter.record` is `Propagation.MANDATORY`, so calling it outside a transaction is a bug that fails fast).
- `OutboxRelay` polls unpublished rows in creation order and sends them to the `payment-events` topic,
  keyed by payment id. All events for one payment therefore land on one partition, in order.
- The relay stops at the first failed send instead of skipping ahead, which preserves per-payment ordering.
- Delivery is **at least once**. Consumers de-duplicate on `eventId`.

## Consequences

- An event is published if and only if its change committed.
- Events lag by up to one poll interval (500 ms by default).
- The outbox table grows. A retention job for published rows is a follow-up.
- With several instances, two relays could send the same row. That is safe because consumers de-duplicate.
  `SELECT ... FOR UPDATE SKIP LOCKED` (MySQL 8) would remove the duplicate work. Debezium CDC is the
  alternative if polling becomes a bottleneck.
