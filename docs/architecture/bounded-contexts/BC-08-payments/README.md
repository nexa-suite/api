# BC-08 — Payments

- **Owner:** `com.nexa.api.payments`
- **Storage:** `payments`.
- **Owns:** provider-neutral payment lifecycle, payment attempts, provider
  adapters, Stripe/webhook inbox and reconciliation workflow.
- **Public contracts:** payment commands, confirmation queries and provider
  boundaries; receivable application is delegated to BC-07 public contracts.
- **v0.15 relation:** applies confirmed payment facts through BC-07 and keeps
  provider identity as a stable reference.
- **Excludes:** credit policy ownership, financial adjustment semantics and
  physical fulfillment.

The shared schema is legacy storage; BC-07 and BC-08 have separate ownership.
