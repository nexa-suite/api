# ADR-012: Row-level security pilot boundary

Status: Accepted — not enabled in FULL-PATCH-1

## Context

Nexa already enforces tenant and workspace scope in the verified access context, application use cases and parametrized persistence queries. PostgreSQL RLS was considered as an additional defense layer for a future pilot.

## Decision

Do not enable RLS silently in this patch. A safe pilot requires a connection-pool-safe transaction boundary that sets and clears tenant/workspace context on every borrowed connection, migration coverage for every scoped table, and concurrency tests proving context cannot bleed between requests. Until those gates exist, the application remains the authoritative authorization boundary and the data network remains private.

## Evidence required before activation

- integration tests with two tenants and concurrent pooled connections;
- explicit `SET LOCAL` or equivalent context lifecycle with rollback/clear proof;
- policy coverage for every tenant/workspace-owned table;
- negative BOLA tests proving both application and database denial;
- runtime inspection showing no context survives connection return.

RLS is therefore recorded as a bounded next step, not represented as implemented security.
