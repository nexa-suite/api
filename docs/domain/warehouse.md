# Warehouse Domain Foundation

## Scope

Warehouse owns physical storage identity and inventory quantities. It is the
future owner of stock, lots, reservations and movements; Catalog Management
continues to own the commercial catalog definition.

## Value objects and vocabulary

- `WarehouseId`, `InventoryItemId` and `InventoryLotId` are required,
  normalized, safe uppercase text identifiers with a 64-character maximum.
- `Quantity` uses `BigDecimal`, rejects negative values, requires an explicit
  unit token and performs no conversion or rounding. Quantities with different
  units must not be compared without an approved conversion rule.
- `StorageCondition` supports `AMBIENT`, `REFRIGERATED` and `FROZEN`. The
  refrigerated/frozen vocabulary follows the existing catalog cold-chain
  terminology; `AMBIENT` is the warehouse-side physical-storage term.
- `InventoryStatus` exposes `AVAILABLE`, `RESERVED`, `QUARANTINED`, `EXPIRED`
  and `DEPLETED`.

The current API has no warehouse aggregate or lifecycle implementation. The
inventory status members and all transitions, reservation semantics, lot
expiry policy and unit vocabulary remain unresolved until that model is
approved. The enums do not authorize state changes.

## Deliberate exclusions

No stock aggregate, reservation service, repository, movement record, JPA
mapping, controller, DTO, tenant field or framework dependency is introduced.
