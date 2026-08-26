# BC-10 — Notifications

- **Owner:** `com.nexa.api.notifications`
- **Storage:** `notifications` plus the BC-01 security-notification queue where
  explicitly documented.
- **Owns:** notification candidates, channel delivery, read state and retry
  behavior.
- **Public contracts:** notification query/command and change-feed audience
  boundaries.
- **v0.15 relation:** consumes published business facts such as delivery and
  fulfillment outcomes; it does not mutate the source aggregate.
- **Excludes:** business traceability, security audit and delivery execution.

Retry/lease behavior remains governed by the existing durable worker contracts.
