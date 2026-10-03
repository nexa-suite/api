# BC-05 — Inventory Availability

- **Owner:** `com.nexa.api.inventoryavailability`
- **Storage:** `warehouse` (legacy physical schema).
- **Owns:** sellable availability, commercial Inventory Backing, lots, FEFO,
  safety stock and v0.15 Physical Allocation.
- **Public contracts:** `InventoryBackingQuery`, `PhysicalAllocationCommands`,
  availability and warehouse operations ports.
- **v0.15 relation:** selects eligible lots, prevents quarantine/hold/safety-stock
  violations, releases unpicked stock and consumes only picked physical stock at
  dispatch.
- **Excludes:** Fulfillment execution, customer-facing delivery outcomes and
  Credit & Receivables.

Inventory Backing and Physical Allocation are separate facts; they are not
parallel names for the same reservation.

## Receiving temperature evidence

For products with an applicable cold-chain requirement, receiving records a
manual temperature reading in Celsius. An in-range reading may omit a photo; an
out-of-range reading requires an available image evidence object bound to the
receiving warehouse. Business Documents owns the image and evidence lifecycle;
Inventory Availability validates that the evidence is available for the exact
`WAREHOUSE` subject before using it.

An excursion records the reading, actor, timestamp, affected received quantity,
and an open temperature evaluation while placing the affected lot on preventive
`HOLD` in the same operation. This context does not automatically release,
reject, waste, return, accept, or destroy stock; an authorized disposition is a
separate decision. Automated sensor and IoT readings remain deferred.
