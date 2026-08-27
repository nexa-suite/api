# Nexa V1 event backbone

The service foundation has one durable authority: `integration.outbox_event`. Domain/application transitions append the canonical envelope atomically with the aggregate change. Consumers claim events with bounded retries and record `(consumer_name,event_id)` in `integration.inbox_event`.

## Envelope

Each event carries `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `tenant_id`, `workspace_id`, `occurred_at`, `correlation_id`, `causation_id`, `schema_version`, and JSONB `payload`. Event IDs are deterministic for an event type and aggregate, so retries and repeated commands are idempotent.

## Frozen canonical publication vocabulary

The Blueprint vocabulary is versioned and must not be replaced by module or
database names. New v0.15 publication uses these exact event types; historical
v0.14 names remain compatibility facts until an explicitly governed migration:

| Bounded context | Event type |
|---|---|
| BC-04 Sales Commitment | `PurchaseRequestSubmitted.v1`, `CommercialCommitmentEstablished.v1`, `SalesOrderConfirmed.v1` |
| BC-05 Inventory Availability | `AvailabilityChanged.v1`, `PhysicalAllocationCreated.v1` |
| BC-06 Fulfillment & Delivery | `FulfillmentShortage.v1`, `DeliveryCompleted.v1`, `ContinuationDeliveryCreated.v1` |
| BC-07 Credit & Receivables | `CreditReservationEstablished.v1`, `ReceivablePosted.v1` |
| BC-08 Payments | `PaymentConfirmed.v1` |
| BC-09 Business Documents | `BusinessDocumentIssued.v1` |
| BC-10 Notifications | `NotificationDeliveryFailed.v1` |
| BC-11 Business Traceability | `BusinessFactTraced.v1` |

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
