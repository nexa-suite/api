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

## Buyer delivery projection

`GET /api/v1/buyer/deliveries`, `GET /api/v1/buyer/deliveries/{deliveryId}`
and the matching `/events` route read actual BC-06 Delivery records created by
Fulfillment handoff. They require `buyer.tracking.read` and derive the active
Customer Account from the current Buyer relationship. The API retains the
legacy `/dispatch-orders` projection separately; a Delivery ID is not treated
as a Dispatch Order ID. The list accepts `page` and `size` (1–100, default
25), ordered by Sales Order creation descending and Delivery creation
descending within an order.

BC-06 reads its own Delivery/Fulfillment facts and resolves Sales Order
references through the BC-04 public query contract. Responses omit account,
fulfillment, driver, assignment and evidence-storage identifiers. The timeline
uses stored handoff, transit, attempt and sealed-POD facts, and omits actor and
reason text.

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
slice supports `OPEN → CLAIMED → UNDER_REVIEW`. The current responsible Driver
may resolve `DELAY` and `INCOMPLETE_INSTRUCTION` warnings through `/resolutions`
with an explicit resolution, then close through the bodyless `/closures` route.
Both commands preserve immutable transitions, actor, time, a server-derived
reason code and the `WARNING_CONDITION_ADDRESSED` outcome. They require the same
Delivery version and idempotency headers. Driver resolution and closure reject
BLOCKING and CRITICAL cases. Claiming or reviewing never changes source severity, clears a
blocking/critical gate, releases stock, or authorizes a Delivery outcome.
Blocking and critical source facts prevent new route attempts and successful
`DELIVERED`/`PARTIAL` outcomes. Failed-attempt and incident/evidence reporting
remain available for documenting the event. Historical facts without an
explicit, supported classification remain unchanged and do not produce a
case.

### Operational Driver location

US-029 separates operational workday state from raw coordinate retention. BC-06 owns authenticated start/end and location-availability facts, and accepts coordinates only for the current actor's ACTIVE workday. BC-01 supplies current Driver eligibility and permissions. New attempt starts require an ACTIVE workday; exact prior attempt replay does not start new work.

Coordinates expire 24 hours after capture. Reads exclude expired points and CLOSED/unavailable workdays. A retention-only database function removes expired coordinates; it exposes no coordinates, accepts no arbitrary predicate and grants no general cross-scope access. The running API invokes it every 30 seconds. Physical purge scheduling must remain active; this local implementation does not establish backup-retention or production operations evidence. Workday events preserve facts without coordinates.

Buyer access additionally requires current Buyer relationship to the Delivery's Customer through BC-02/BC-04 public contracts and an actually dispatched, nonterminal Delivery. Dispatch location access requires current internal Logistics/Owner role and dispatch assignment authority. Warehouse and Sales do not receive Driver coordinates. Client capture must stop before ending a workday and whenever local authority or OS location availability is lost.

## Customer delivery instruction provenance

Customer instructions can be recorded before a Delivery is materialized. BC-06 stores
immutable revisions anchored to the authoritative BC-04 SalesOrder. Buyer writes
require the active BC-02 Customer relationship; Sales writes require `client.manage`
and an explicit reference to the instruction received from the Customer. The server
records the actual actor and source (`BUYER` or `CUSTOMER_REPORTED_BY_SALES`).
Neither chat messages nor Sales submissions impersonate a Buyer acknowledgement.

`GET/POST /api/v1/sales-orders/{id}/customer-delivery-instructions` serves Sales;
the corresponding `/api/v1/buyer/sales-orders/{id}/customer-delivery-instructions`
serves the related Buyer. Mutations use a strong instruction-set `If-Match` and a
caller-owned idempotency key. Editing closes permanently after physical readiness,
even if a later plan change returns the Fulfillment to an earlier state. Terminal
SalesOrders also reject new edits. Existing idempotent commands remain recoverable
under current authorization.

Delivery planning and physical handover copy the frozen revisions to a Delivery,
retaining their source revision, author and recording time. Split Deliveries receive
separate instruction identities linked to the same source revision. Critical
instructions require the assigned Driver's acknowledgement of the exact revision;
acknowledgement does not confer stock, disposition or Delivery outcome authority.

## Grouped Delivery loads

Dispatch can group 2–20 current `READY_FOR_DISPATCH` Fulfillments from one authorized
Warehouse into a manually ordered load. Planning creates one `PLANNED` Delivery per
stop and does not move stock or decide a Delivery outcome. The server verifies
current allocation, readiness, holds, assignment, temperature profile, and delivery
window facts. Dispatch records affirmative capacity, handling, zone, and exclusive-
transport attestations with the authenticated actor and server time.

Every stop must have an authoritative delivery window, and windows must overlap.
The load API does not accept or replace delivery windows. For a READY Fulfillment
without a commercial window, authorized Dispatch may append one plan through
`POST /api/v1/fulfillments/{id}/dispatch-window-plans`; the plan records reason,
actor and time, is immutable, and is rejected if a commercial window or another
plan already exists. Replanning is a separate decision. Missing or conflicting
window facts reject grouping; they do not become compatible by Dispatch attestation.
Active temperature policies and persisted operational ranges remain authoritative;
the explicit no-restriction profile is compatible only with the same profile.

Dispatch assigns one currently eligible Driver to the whole load. Assignment also
creates the canonical Fulfillment and planned-Delivery responsibilities so the
Driver can review the stops and current critical instructions before handover.
Dispatch offers the load; Driver acceptance and Dispatch handover confirmation may
arrive in either order. The second decision rechecks current dispatch readiness and
records `RESPONSIBILITY_TRANSFERRED`. Warehouse handover then runs the existing
physical checks and reuses each planned Delivery rather than creating a duplicate.
Planned Deliveries remain non-startable until physical handover, and critical
instruction acknowledgement remains a separate route-execution gate.

Load creation, ordering, assignment, offer, confirmation, and Driver acceptance have
actor-scoped idempotency and append-only history. Existing load mutations require a
strong version `If-Match`; current tenant, workspace, Warehouse-grant, Driver
assignment, and permission checks apply on reads, writes, and replays. See
[`delivery-loads.md`](../../../openapi/delivery-loads.md) for routes and payloads.

## Business Operations Manager exception coordination

The `BUSINESS_OPERATIONS_MANAGER` PLATFORM role receives only explicit
`delivery.exception.read` and `delivery.exception.coordinate` grants. The scoped
coordination API reads source-linked cases and appends claim, assignment, follow-up,
warning resolution and closure facts. Assignment candidates come from BC-01's
current effective-capability projection and Driver reporters are offered only while
still assigned to that Delivery. Resolution and closure are limited to typed
warnings after an authoritative Delivery outcome. Coordination never releases a
Delivery execution HOLD, disposes inventory, or changes delivery outcome; thermal
release still requires the separate authorized disposition fact. See
[`operational-exceptions.md`](../../../openapi/operational-exceptions.md).


### In-transit temperature execution HOLD

BC-06 owns `delivery_execution_temperature_evidence`, `delivery_execution_hold` and `delivery_execution_disposition`. These append-only facts link actual Delivery/Fulfillment quantity, typed Driver incident and exact available photo. Explicit disposition authority is separate from exception coordination. Normal execution remains blocked until an authorized RELEASE; REJECT/WASTE require explicit subsequent handling. No BC-05 stock is recreated. See [HTTP contract](../../../openapi/delivery-execution-temperature.md).
