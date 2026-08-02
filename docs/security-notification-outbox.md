# Security notification outbox

The security notification outbox is at-least-once. A worker claims a bounded batch with PostgreSQL `FOR UPDATE SKIP LOCKED`, marks each row `PROCESSING`, and delivers it outside the claim query. Retries use controlled backoff and end in `DEAD_LETTER`; `delivery_key` remains stable and reset links are single-use, so an uncertain SMTP result can only produce a safe duplicate notification.

Payloads use AES-GCM. `NEXA_NOTIFICATION_OUTBOX_KEY` and `NEXA_NOTIFICATION_OUTBOX_KEY_VERSION` identify the current key. During rotation, configure `NEXA_NOTIFICATION_OUTBOX_PREVIOUS_KEYS` as comma-separated `version=secret` entries until all rows using the old version reach terminal retention. Keys are supplied only through deployment configuration; they are never stored in Git or logged. Local development must use a test-only key, while production rotation must retain previous keys for the configured delivery and retention window.

After `SENT`, ciphertext is erased while delivery metadata remains. Failed terminal rows also erase ciphertext; the retention job removes terminal reset metadata after 90 days by default. Audit records default to 365 days and all retention values are configurable through `NEXA_SECURITY_*_RETENTION_*` settings.
