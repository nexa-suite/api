# Foundation worker contracts

Status: accepted for API v0.13.0 foundation closure. This document describes technical invariants; it does not introduce a new product bounded context or event type.

## Durable work

Every durable processor uses a bounded claim, an explicit lease, a per-attempt claim token, bounded retries, and a dead-letter or operator-visible terminal state. A processor reconstructs tenant/workspace scope before scoped SQL and clears the scope in `finally`. External completion must include the current claim token and an unexpired lease.

Covered queues:

- `integration.outbox_event`: canonical events and `integration.inbox_event` consumer deduplication.
- `payments.stripe_event_inbox`: signed webhook inbox with claim fencing.
- `payments.payment_reconciliation_case`: provider refund compensation with `REFUND_PROCESSING` claim fencing.
- `business_documents.document_generation_request`: renderer/storage generation with stale recovery that returns the related document from `GENERATING` to `REQUESTED`.
- `business_documents.evidence_object`: storage upload and content scan claims.
- `iam.security_notification_outbox`: encrypted security notifications with SMTP delivery key propagation.

`integration.outbox_event` retention is bounded and non-destructive: published payloads older than the configured window are redacted in batches, while event identity, ordering metadata, status, and inbox deduplication history remain durable. Pending, processing, failed, and dead-letter payloads are never redacted.

## External provider boundary

Stripe refund calls execute outside database transactions. The reconciliation case UUID is the stable Stripe idempotency key across timeouts, process restarts, and bounded retries. A provider response is durable only after a fenced database finalization; an uncertain response remains retryable and is never converted to a fabricated success.

Manual refund commands persist the tenant/workspace/membership scope, request hash, durable result, and technical outcome. Reusing a key with another case or operator note is a deterministic conflict; replay returns the stored result.

SMTP is at-least-once. The stable `delivery_key` crosses the adapter boundary as `X-Nexa-Delivery-Key`; the outbox never clears ciphertext or marks `SENT` until the current claim owns the row.

## Scope classification

Direct tenant/workspace RLS is enforced and inventoried by `ModernPostgresMigrationTests`. Parent-derived child tables remain application-scoped through tenant/workspace foreign keys and their parent policy; technical queues without business scope (for example the security notification outbox) are global worker infrastructure and carry their own durable identity, retry, and audit fields.
