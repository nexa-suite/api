# BC-06 — Fulfillment & Delivery

- **Owner:** `com.nexa.api.fulfillmentdelivery`
- **Storage:** `logistics` (legacy physical schema), including compatibility
  dispatch tables and v0.15 fulfillment/delivery facts.
- **Owns:** picking, packing, staging, dispatch handover, delivery attempts,
  quantity outcomes, continuation delivery, POD and temperature evidence.
- **Public contracts:** `FulfillmentPersistencePort`, `DeliveryPersistencePort`
  and `FulfillmentLifecycleService`; HTTP routes are under `/api/v1`.
- **v0.15 lifecycle:** `ALLOCATED → PICKING → PICKED → PACKED → STAGED →
  READY_FOR_DISPATCH → HANDED_OVER`, then delivery `IN_TRANSIT → PARTIAL` or
  `DELIVERED/FAILED`.
- **Excludes:** stock ownership, sales-order ownership, payment provider
  execution and Security Audit.

Discrepancies are immutable observations; final shortage resolution is a
separate append-only fact.
