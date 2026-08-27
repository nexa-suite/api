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
