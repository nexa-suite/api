# Operational delivery instructions

BC-06 owns Operational Dispatch Instructions. Dispatch Coordinators and BOM
members publish revisions under current `dispatch.schedule` authority and the
existing dispatch-read/Warehouse-grant check. Operational Dispatch commands do
not create or revise Customer Delivery Instructions. Read projections may
include immutable Customer-source revisions. Driver routes use the current
`dispatch.read` and `dispatch.start_route` permissions; assignment remains the
server-side access boundary.

`GET /api/v1/deliveries/{deliveryId}/instructions` returns the current instruction
set to a Dispatch actor with current `dispatch.read` permission and Warehouse
grant. The server reuses BC-06 dispatch-readiness authority for the exact Delivery.
The response uses the same `InstructionSetView` fields as the Driver projection,
including source and recording provenance. Its strong ETag is the quoted
`deliveryVersion`, which is the concurrency token required by Dispatch publish.
Customer-source IDs remain read-only; only current `OPERATIONAL_DISPATCH` rows may
be revised through the publish route.

`GET /api/v1/driver/deliveries/{deliveryId}/instructions` returns only the
currently assigned Driver's instructions while the Delivery is `PLANNED`,
`ASSIGNED`, `DISPATCHED` or `IN_TRANSIT`. Response contains `deliveryId`, `deliveryVersion`,
`instructionSetVersion` and `instructions[]`. Each instruction contains `id`,
`kind`, `content`, `instructionVersion`, server-derived `critical`,
`acknowledged`, and current-actor `acknowledgedAt` and
`acknowledgedByMembershipId` when acknowledged. Response ETag carries the
`instructionSetVersion`.

`POST /api/v1/driver/deliveries/{deliveryId}/instruction-acknowledgements`
requires the instructions ETag in `If-Match` and an `Idempotency-Key`. Request
body is `{ "instructionIds": ["<uuid>"] }`. Only current critical instruction
revisions can be acknowledged. Response contains `deliveryId`,
`instructionSetVersion`, `acknowledgements[]` with `instructionId`,
`instructionVersion`, `acknowledgedByMembershipId` and `acknowledgedAt`, plus
`replayed`.

`POST /api/v1/deliveries/{deliveryId}/instructions` requires the Delivery ETag
in `If-Match` and an `Idempotency-Key`. Request body is
`{ "instructionId": "<uuid, optional>", "kind": "NORMAL", "content": "..." }`.
Supported kinds are `NORMAL`, `COLD_CHAIN`, `ACCESS_RESTRICTION`,
`SPECIAL_UNLOADING`, `CUSTOMER_SAFETY` and `GOODS_HANDLING`. Omit
`instructionId` to create an instruction; provide it to append a new immutable
revision. Every kind except `NORMAL` is critical. The response identifies the
instruction revision, resulting Delivery version and instruction-set version.

Instruction publication, acknowledgement and a new Driver attempt serialize
on the same Delivery row. A new attempt fails with `409
DELIVERY_CRITICAL_INSTRUCTION_ACK_REQUIRED` if current critical revisions lack
acknowledgement from the current assigned Driver. Wrong or inactive Driver
scope is hidden as `404`; stale `If-Match` receives `412`.

`PARTIAL` deliveries are excluded from Driver read and acknowledgement in this
slice. BC-06 does not yet expose the canonical continuation/closure predicate
needed to distinguish an active legacy partial from a parent Delivery already
closed by a continuation, so this conservative status boundary avoids exposing
closed-parent instructions.
