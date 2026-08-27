# BC-07 — Credit & Receivables

- **Owner:** `com.nexa.api.creditreceivables`
- **Storage:** `payments` legacy schema during the additive migration.
- **Owns:** credit exposure/reservations, receivables, applications, financial
  adjustments, ledger entries and refund/credit obligations.
- **Public contracts:** `CreditExposureQuery`, `CreditReservationCommands`,
  `ReceivableCommands`, `ReceivableApplicationCommands` and
  `FinancialAdjustmentCommands`.
- **v0.15 relation:** final picking/delivery quantity resolution posts immutable
  adjustments and preserves stable payment/document references without a
  cross-context database FK.
- **Excludes:** provider payment lifecycle, Stripe/webhook execution and
  document rendering.

BC-07 is a standalone context, not a Payments subpackage.
