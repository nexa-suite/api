# Delivery execution temperature and dispositions

BC-06 owns immutable readings, execution HOLDs and disposition facts for goods already handed over. These commands do not recreate Inventory Availability or modify commercial, financial or Buyer Receipt facts.

## Authorization and scope

Driver reads require `dispatch.read` and current Delivery assignment. Driver recording requires `dispatch.start_route` and current assignment. Internal reads and dispositions require explicit `delivery.execution_hold.dispose` and an active grant for the actual origin Warehouse. No built-in Driver or BOM role receives disposition authority through reporting or coordination.

All routes use authenticated Tenant/Workspace. Commands require a strong numeric Delivery `If-Match` and `Idempotency-Key`. Exact actor-scoped retries replay the recorded fact under current access; changed payloads conflict. Fresh commands check current state and version.

## Routes

- `GET /api/v1/driver/deliveries/{deliveryId}/execution-temperature-readings`
- `GET /api/v1/deliveries/{deliveryId}/execution-temperature-readings`
- `POST /api/v1/driver/deliveries/{deliveryId}/execution-temperature-readings`
- `POST /api/v1/deliveries/{deliveryId}/execution-holds/{holdId}/dispositions`

GET returns Delivery version/status, active attempt if present, actual origin Warehouse, remaining Fulfillment lines with SKU/unit/range, and current HOLDs. The ETag is the current Delivery version.

Reading request fields: `fulfillmentLineId`, `skuId`, positive `affectedQuantity`, `value`, `unit` (`CELSIUS`), `occurredAt`, nullable `sourceIncidentId` and `evidenceObjectId`. Capture cannot be in the future. Coordinate retention policy does not impose a retention or reporting deadline on temperature evidence. Quantity cannot exceed remaining physical quantity; SKU and line must belong to this Fulfillment. Server obtains the current authoritative SKU cold-chain range.

An excursion requires an actual Driver `TEMPERATURE_EXCURSION` incident for this Delivery and reporter, its CRITICAL exception, and an AVAILABLE photo belonging to that exact `DELIVERY_INCIDENT` subject. Reporting severity remains server-derived. A previously held incident cannot create another HOLD. In-range readings do not fabricate incidents or HOLDs.

Reading response preserves measurement, policy range, quantity/unit, authenticated actor, capture/recording time, attempt if applicable, source incident, evidence and recorded Delivery version. `temperatureUnit` is `CELSIUS`; status is `WITHIN_RANGE` or `OUT_OF_RANGE`. The optional HOLD projection reflects its current disposition. Reading facts remain immutable. Fresh responses use 201; replay responses use 200 with `replayed=true` and the recorded version ETag. Refresh before a subsequent command.

Disposition request fields: `disposition` and nonblank `reason`. Allowed values are `RELEASE`, `CONTINUE_HOLD`, `REJECT`, `WASTE`. Fresh disposition requires a currently HELD quantity. Response contains `{hold, deliveryVersion, replayed}` with recorded version ETag, 201 for fresh and 200 for replay. Disposition history preserves actor, sequence, timestamp, reason and version.

## Execution consequences

Normal Delivery transitions, successful POD mutation and fresh Buyer receipt cannot bypass a blocking execution fact. V1 applies a conservative Delivery-level stop while any affected quantity remains HELD, REJECTED or WASTED; it does not offer partial continuation of unaffected quantities. RELEASE permits normal execution to resume subject to every other current invariant. CONTINUE_HOLD preserves the stop. REJECT/WASTE require their explicit subsequent operational treatment; they do not automatically create a Delivery result or stock return.

BOM administrative closure alone cannot release a thermal execution HOLD. Historical ambiguous incidents are not reclassified. Persisted readings, HOLDs and dispositions are append-only and protected by forced Tenant/Workspace RLS.

## Verification status

Source implementation and focused test cases are present. Build, HTTP integration and Android execution remain pending the final verification phase; this document does not establish acceptance or production readiness.
