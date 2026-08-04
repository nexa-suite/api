# Nexa V1 event backbone

The service foundation has one durable authority: `integration.outbox_event`. Domain/application transitions append the canonical envelope atomically with the aggregate change. Consumers claim events with bounded retries and record `(consumer_name,event_id)` in `integration.inbox_event`.

## Envelope

Each event carries `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `tenant_id`, `workspace_id`, `occurred_at`, `correlation_id`, `causation_id`, `schema_version`, and JSONB `payload`. Event IDs are deterministic for an event type and aggregate, so retries and repeated commands are idempotent.

## V1 chain

| Fact | Consumer effect |
|---|---|
| `PURCHASE_REQUEST_SUBMITTED` | Sales notification |
| `PURCHASE_REQUEST_APPROVED` | Idempotent Sales Order conversion |
| `SALES_ORDER_CONFIRMED` | FEFO Warehouse reservation |
| `FULFILLMENT_READY` | Idempotent Logistics Dispatch creation |
| `DISPATCH_DELIVERED` | POD, delivery notification, and delivery-guide/order/POD document requests |
| `DELIVERY_COMPLETED` / `POD_COMPLETED` | Durable delivery/POD facts and notification projection |
| `INVOICE_ISSUED` / `PAYMENT_SUCCEEDED` | Receivable/payment notification and document receipt request |

Cross-context effects execute through inbound application ports. No frontend call chain is required for the downstream transitions. Worker failures remain `PENDING`/`FAILED` with exponential backoff and become `DEAD_LETTER` only after the bounded attempt limit; the source fact is never discarded.
