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

## Driver incidents and operational exceptions

The assigned Driver incident route is `POST
/api/v1/driver/deliveries/{deliveryId}/attempts/{attemptId}/incidents`. New
reports require an explicit `type`: `DELAY`, `INCOMPLETE_INSTRUCTION`,
`ACCESS_BLOCKED`, `CUSTOMER_UNAVAILABLE`, `DELIVERY_NOT_EXECUTABLE`,
`TEMPERATURE_EXCURSION`, or `SAFETY_COMPROMISING_DAMAGE`. The API derives
severity; clients cannot supply it. The first two types are `WARNING`, the
access/customer/non-executable types are `BLOCKING`, and temperature or
safety-compromising damage is `CRITICAL`. Reports retain the current actor,
attempt, reason, description, place, timestamp and exact source identity.
An exact replay of a previously saved untyped incident command remains
available with nullable type, severity and case ID; it is not reclassified.

Assigned Drivers read current cases from `GET
/api/v1/driver/deliveries/{deliveryId}/operational-exceptions`. They claim an
open unassigned case with `POST
/api/v1/driver/deliveries/{deliveryId}/operational-exceptions/{exceptionId}/claims`
and the responsible Driver begins review with the corresponding `/reviews`
route. These commands have no request body. Read and command responses carry
the current Delivery version as a strong quoted `ETag`; commands require that
value in `If-Match` and require `Idempotency-Key`. New transitions return
`201`, exact replays return `200`, stale versions return `412`, and invalid
transitions or blocking gates return `409`. Scope is limited to the current
assigned Driver and an active Delivery; out-of-scope cases return `404`.

Cases point to an immutable Dispatch or Driver incident source. This Driver
slice supports `OPEN → CLAIMED → UNDER_REVIEW`; it has no resolution or close
command. Claiming or reviewing never changes source severity, clears a
blocking/critical gate, releases stock, or authorizes a Delivery outcome.
Blocking and critical source facts prevent new route attempts and successful
`DELIVERED`/`PARTIAL` outcomes. Failed-attempt and incident/evidence reporting
remain available for documenting the event. Historical facts without an
explicit, supported classification remain unchanged and do not produce a
case.
