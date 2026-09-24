# Sales Domain Foundation

> Historical foundation note: this document records API-era domain analysis,
> not current Product, Domain or implementation authority. See the [Nexa
> Blueprint](https://github.com/nexa-suite/blueprint) for accepted meaning and
> the [current API module map](../architecture/bounded-context-module-map.md)
> for implementation ownership.

## Scope

Sales owns the commercial identity of a purchase request, a sales order and the
client account referenced by those workflows. This foundation contains only
framework-free value objects and closed status vocabularies. It does not own
catalog definitions, warehouse stock, shipment execution or fiscal documents.

## Value objects

- `PurchaseRequestId`, `SalesOrderId` and `ClientAccountId` are distinct types
  even when an integration supplies the same textual representation.
- Identifier values are required, trimmed, uppercased with `Locale.ROOT`,
  restricted to `A-Z`, digits and `-`, and limited to 64 characters.
- No identifier prefix is enforced because the current API documentation and
  code do not establish one for these contexts.

## Status vocabulary

`PurchaseRequestStatus` currently exposes `DRAFT`, `SUBMITTED`, `APPROVED`,
`REJECTED` and `CANCELLED`. `SalesOrderStatus` currently exposes `DRAFT`,
`CONFIRMED`, `COMPLETED` and `CANCELLED`.

At the time this foundation was recorded, the repository README classified
Sales as planned and this repository contained no implemented sales lifecycle
or transition rules. Therefore these enum members were candidate vocabulary
only: the authoritative states, transitions,
reopening policy and mapping to external contracts remain unresolved. No
application service or HTTP contract may infer those rules from the enums.

## Deliberate exclusions

There are no aggregates, repositories, services, controllers, DTOs, JPA
annotations, tenant fields or cross-context entity references in this
foundation.
