# Missing Dispatch window plans

`POST /api/v1/fulfillments/{fulfillmentId}/dispatch-window-plans` records one append-only Dispatch window plan when the READY Fulfillment has no existing commercial Delivery window.

The request requires a strong Fulfillment `If-Match` ETag and an `Idempotency-Key`:

```json
{
  "windowStart": "2026-10-02T14:00:00Z",
  "windowEnd": "2026-10-02T17:00:00Z",
  "reason": "Customer confirmed revised receiving hours"
}
```

The current Dispatch actor must hold `dispatch.read` and `dispatch.schedule` plus a current grant for the Fulfillment's Warehouse. The server requires `READY_FOR_DISPATCH`, validates `windowStart < windowEnd`, preserves any existing commercial window, and records actor and server timestamp. The command increments Fulfillment version; response ETag is the new Fulfillment version. Exact same-key replay returns HTTP 200; first record returns HTTP 201. Stale ETag returns `412 PRECONDITION_FAILED`; existing legacy/planned window returns `409 FULFILLMENT_DISPATCH_WINDOW_ALREADY_DEFINED`.

Dispatch readiness exposes the effective `windowStart`, `windowEnd`, and `windowSource` (`COMMERCIAL` or `DISPATCH_PLAN`). Existing commercial windows remain authoritative. A window plan does not rewrite SalesOrder, DispatchOrder, or Delivery window fields. Rescheduling is outside this contract.
