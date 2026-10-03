# Delivery load API

Delivery loads group two to twenty `READY_FOR_DISPATCH` Fulfillments from one authorized Warehouse. Load planning creates a `PLANNED` Delivery for each stop without changing stock. Dispatch compatibility attestations are recorded with the authenticated actor and timestamp; compatibility facts that are missing, inconsistent, held, already assigned, or incompatible fail closed.

All routes require the current tenant/workspace access context. Dispatch routes require the existing `dispatch.read`, `dispatch.schedule`, or `dispatch.assign` permission as described below. Driver routes use the current assigned membership and `dispatch.read` or `dispatch.start_route`. Warehouse scope is checked against current Warehouse grants.

## Routes

| Method and route | Authority | Purpose |
| --- | --- | --- |
| `GET /api/v1/dispatch/loads` | `dispatch.read` | List loads in current Warehouse grants. |
| `POST /api/v1/dispatch/loads` | `dispatch.read` + `dispatch.schedule` | Create a load and its planned Deliveries. Requires `Idempotency-Key`; no `If-Match` because the load does not yet exist. |
| `GET /api/v1/dispatch/loads/{loadId}` | `dispatch.read` | Read a load in current Warehouse grants. |
| `PUT /api/v1/dispatch/loads/{loadId}/stops` | `dispatch.schedule` | Replace the complete stop order. Requires `If-Match` and `Idempotency-Key`. |
| `POST /api/v1/dispatch/loads/{loadId}/assignments` | `dispatch.assign` | Assign one current eligible Logistics membership to every stop. Requires `If-Match` and `Idempotency-Key`. |
| `POST /api/v1/dispatch/loads/{loadId}/offers` | `dispatch.schedule` | Offer the whole load to the assigned Driver. Requires `If-Match` and `Idempotency-Key`. |
| `POST /api/v1/dispatch/loads/{loadId}/handoff-confirmations` | `dispatch.schedule` | Confirm dispatch readiness for the whole load. Requires `If-Match` and `Idempotency-Key`. |
| `GET /api/v1/driver/loads` | `dispatch.read` | List loads assigned to the current Driver membership. |
| `POST /api/v1/driver/loads/{loadId}/acceptances` | `dispatch.start_route` | Accept the whole load. Only its currently assigned Driver can accept. Requires `If-Match` and `Idempotency-Key`. |

Load detail and mutation responses include `ETag: "{version}"`. Mutations require that exact strong ETag in `If-Match`; a stale version returns `412`. Missing preconditions return `428`. Mutations require an `Idempotency-Key`; replaying the same actor-scoped key and body returns the recorded result, while reusing it with a different body returns `409`.

## Create request

```json
{
  "fulfillmentIds": ["fulfillment-uuid-1", "fulfillment-uuid-2"],
  "stopOrder": ["fulfillment-uuid-1", "fulfillment-uuid-2"],
  "expectedFulfillmentVersions": {
    "fulfillment-uuid-1": 4,
    "fulfillment-uuid-2": 7
  },
  "reason": "Compatible deliveries for one route",
  "compatibilityAttestation": {
    "capacitySufficient": true,
    "handlingCompatible": true,
    "zoneReasonable": true,
    "noExclusiveTransportRestriction": true,
    "observation": "Dispatch review"
  }
}
```

The ID arrays contain the same unique Fulfillment IDs. `stopOrder` is the initial manual order. The server derives the Warehouse from current physical allocation facts, verifies current readiness and holds, resolves active BC-05 SKU temperature policy, and requires compatible delivery-window facts. It records the authenticated Dispatch membership and server time for the compatibility attestation; the request cannot choose the actor or timestamp. Missing or conflicting temperature/window facts reject the creation.

## Load response

```json
{
  "id": "uuid",
  "version": 0,
  "status": "DRAFT",
  "originWarehouseId": "uuid",
  "stops": [
    { "fulfillmentId": "uuid", "deliveryId": "uuid", "position": 1, "deliveryVersion": 1 }
  ],
  "assignedDriverMembershipId": null,
  "vehicleReference": null,
  "compatibilityAttestation": {
    "capacitySufficient": true,
    "handlingCompatible": true,
    "zoneReasonable": true,
    "noExclusiveTransportRestriction": true,
    "attestedByMembershipId": "uuid",
    "attestedAt": "2026-10-01T12:00:00Z",
    "observation": "Dispatch review"
  },
  "offeredByMembershipId": null,
  "offeredAt": null,
  "dispatchConfirmedByMembershipId": null,
  "dispatchConfirmedAt": null,
  "driverAcceptedByMembershipId": null,
  "driverAcceptedAt": null,
  "history": [
    { "eventType": "CREATED", "actorMembershipId": "uuid", "occurredAt": "2026-10-01T12:00:00Z", "reason": "Compatible deliveries for one route", "affectedDriverMembershipId": null, "previousStopOrder": [], "newStopOrder": ["delivery-uuid-1", "delivery-uuid-2"], "compatibilityAttestation": { "capacitySufficient": true, "handlingCompatible": true, "zoneReasonable": true, "noExclusiveTransportRestriction": true, "attestedByMembershipId": "uuid", "attestedAt": "2026-10-01T12:00:00Z", "observation": "Dispatch review" } }
  ]
}
```

The lifecycle is `DRAFT` → `ASSIGNED` → `OFFERED`; Driver acceptance and Dispatch confirmation may arrive in either order. The load reaches `RESPONSIBILITY_TRANSFERRED` only after both current actors have recorded their decisions and the second decision rechecks current dispatch readiness. Physical handover then reuses each planned Delivery, performs the existing Warehouse checks, and records its normal handoff evidence. A load assignment also creates the canonical per-Fulfillment and planned-Delivery assignment so its Driver can review and acknowledge current critical instructions before execution. Planned Deliveries are not startable; the existing current-revision acknowledgement gate still applies to route execution.

## Reorder request

```json
{
  "stopOrder": ["fulfillment-uuid-2", "fulfillment-uuid-1"],
  "reason": "Route sequence updated"
}
```

## Assignment request

```json
{
  "driverMembershipId": "uuid",
  "vehicleReference": "optional operational reference"
}
```

Requests outside current tenant/workspace, Warehouse grant, or assigned-Driver scope return `404`. Non-ready Fulfillments, holds, incompatible windows/temperature requirements, existing assignments, and invalid state transitions return `409`. No client field changes Delivery outcome, releases stock, or resolves an operational exception.
