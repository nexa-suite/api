# BC-11 — Business Traceability

- **Owner:** `com.nexa.api.businesstraceability`
- **Storage:** existing append-only `audit.event` plus `integration.outbox_event`
  and inbox infrastructure.
- **Owns:** business-fact trace records and their durable timeline/publication
  references.
- **Public contracts:** `BusinessTraceabilityCommands` and the trace query
  boundary; `BusinessFactTraced.v1` is the v0.15 publication envelope.
- **v0.15 relation:** records allocation, shortage, delivery, financial and
  evidence facts atomically with their owner-context mutation.
- **Excludes:** BC-01 Security Audit (`iam.security_audit_event`), provider
  reconciliation and a duplicate audit subsystem.

`audit.event` is the existing physical compatibility store; the canonical
bounded-context name is Business Traceability.
