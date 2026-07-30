# Logistics Domain Foundation

## Scope

Logistics owns shipment and dispatch identity, delivery timing and temperature
observations. It does not own the sales order, warehouse inventory or invoice
aggregate.

## Value objects and vocabulary

- `ShipmentId` and `DispatchOrderId` are required, normalized, safe uppercase
  text identifiers with a 64-character maximum.
- `ShipmentStatus` provides `PLANNED`, `DISPATCHED`, `IN_TRANSIT`, `DELIVERED`,
  `FAILED` and `CANCELLED` as candidate logistics vocabulary.
- `TemperatureReading` stores an exact `BigDecimal` value, a required
  `TemperatureUnit` (`CELSIUS` or `FAHRENHEIT`) and a required UTC-capable
  `Instant` timestamp. Negative readings are valid for frozen-chain operation;
  no conversion or acceptable-range policy is encoded.
- `DeliveryWindow` stores `startsAt` and `endsAt` and rejects null bounds or a
  zero/negative interval.

The repository contains no implemented logistics workflow, carrier contract,
temperature threshold or shipment transition policy. Status members,
thresholds, scan cadence, carrier identity and failure/retry semantics remain
unresolved and must not be inferred from these primitives.

## Deliberate exclusions

No dispatch aggregate, tracking service, carrier adapter, repository,
controller, DTO, JPA mapping or framework dependency is introduced.
