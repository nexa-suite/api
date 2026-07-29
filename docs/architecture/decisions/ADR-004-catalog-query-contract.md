# ADR-004: Catalog query contract

Status: Accepted for API `v0.4.0`

## Context

Catalog Management needs a read-only vertical slice without leaking domain aggregates or legacy persistence concerns through HTTP. The 50-item seed is the approved local source of truth for this slice.

## Decision

- The Application layer exposes `ListCatalogItemsUseCase` and `GetCatalogItemUseCase`.
- `CatalogItemQueryPort` returns immutable application models, never domain aggregates.
- `CatalogSearchCriteria` supports bounded `q`, `brand`, `category`, `coldChain`, `page`, `size`, `sort` and `direction` values.
- Infrastructure owns the seed adapter and explicit projection from Domain to Application.
- Presentation owns request binding, response DTOs, money/media mapping and Problem Details.
- `GET /api/v1/catalog-items` and `GET /api/v1/catalog-items/{catalogItemId}` expose only active commercial catalog data.

## Consequences

The contract is transport-stable and testable without JPA or PostgreSQL. Stock, promotion, tenant, workspace and persistence state remain outside this bounded context and response shape.
