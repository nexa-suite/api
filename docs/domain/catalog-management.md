# Catalog Management Domain

## Purpose

Catalog Management owns the commercial product definition used to describe a sellable catalog item. It does not own physical inventory or tenant assignment.

## Ubiquitous Language

- **Catalog Item:** commercial definition identified by a stable catalog item ID.
- **Product:** source product identity associated with a catalog item.
- **Brand:** represented here by `BrandName`; no independent lifecycle is established.
- **Category:** represented here by `CategoryName`; no independent lifecycle is established.
- **Unit Price:** non-negative amount with ISO currency and at most two decimals.
- **Presentation:** commercial format or pack description.
- **Cold-Chain Requirement:** `NONE`, `REFRIGERATED` or `FROZEN`.
- **Catalog Media:** validated relative image URL and safe filename.
- **Active / Inactive:** catalog publication status.
- **Available Stock:** external concept owned by Warehouse.

Catalog Item is not Inventory Item, Inventory Lot or Invoice Amount. Catalog Price is not Invoice Amount. Brand Name is not Brand aggregate. Category Name is not Category aggregate.

## Aggregate

`CatalogItem` is the only aggregate in this foundation. It is final, framework-free, identity-based, state-encapsulated and created active through `CatalogItem.create(...)`.

Intent methods are `rename`, `changeBrand`, `reclassify`, `rewriteDescription`, `changePresentation`, `changeUnitPrice`, `changeColdChainRequirement`, `changeMedia`, `activate` and `deactivate`.

## Value Objects

`CatalogItemId`, `ProductId`, `ItemName`, `BrandName`, `CategoryName`, `CatalogDescription`, `ProductPresentation`, `Money`, `ColdChainRequirement` and `CatalogMedia` protect input and representation rules. `CatalogItemStatus` represents `ACTIVE` and `INACTIVE`.

## Invariants

- Required values reject null and blank input.
- Catalog IDs use safe uppercase `CAT-` identifiers up to 64 characters.
- Product IDs use safe uppercase `PROD-` identifiers up to 64 characters.
- Names and descriptions trim input and enforce historical maximums.
- Money is non-negative, ISO 4217 and no more than two decimals; no floating point is used.
- Cold-chain parsing is case-insensitive for legacy values and rejects unknown values.
- Media uses a relative `/catalog-items/` path, safe filename, no traversal, query, fragment or backslash, and matching URL/filename.
- Activation and deactivation are idempotent.

## Ownership

Catalog Management owns commercial definition, naming, pricing representation, presentation, media and publication status. Warehouse owns physical stock, lots, reservations and movements. Tenant ownership remains a future IAM and Tenant Management decision.

## Legacy differences

The legacy aggregate mixed catalog definition with `AvailableStock`, `ReserveStock`, `SynchronizeAvailableStock`, tenant assignment, command objects, base entities and persistence concerns. This foundation excludes them from Domain. It also does not migrate Brand or Category as aggregates.

## Seed mapping

`CatalogSeedItemRecord` remains an Infrastructure import format. `CatalogSeedDomainMapper` maps the 50 validated records to `CatalogItem` objects while preserving IDs, names, price, cold-chain value, media and presentation. `availableStock`, `sourcePriceCode` and `sourcePriceDescription` remain import metadata and never become aggregate state. `loadDomainCatalog()` preserves seed order and returns an immutable list.

## Deliberately excluded concepts

No tenant ID, stock quantity, reservation, repository, entity base class, domain event, persistence mapping, DTO, controller, HTTP endpoint or framework annotation belongs in this foundation.

## Future integration points

Application use cases may later load, edit or publish catalog items through explicit ports. Presentation may map future contracts to client-specific DTOs. Persistence, tenant ownership, authorization and independent Brand/Category lifecycle require separate approved decisions.
