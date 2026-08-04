# ADR-012: Row-level security pilot boundary

Status: Accepted — selective RLS enabled in Service Foundation V1

## Context

Nexa enforces tenant and workspace scope in the verified access context, application use cases and parametrized persistence queries. Service Foundation V1 adds PostgreSQL RLS to the highest-risk Sales, Documents, Payments and Notification tables as a defense-in-depth boundary.

## Decision

Enable selective RLS through a connection-pool-safe wrapper that sets and clears tenant/workspace context on every borrowed connection. `CurrentAccessContextFilter` sets the request scope only after the active membership and authorization version are revalidated. The application remains the primary authorization boundary; RLS is an additional database boundary, not a replacement for permissions.

## Evidence required before activation

- integration tests with two tenants and concurrent pooled connections;
- explicit `SET LOCAL` or equivalent context lifecycle with rollback/clear proof;
- policy coverage for every tenant/workspace-owned table;
- negative BOLA tests proving both application and database denial;
- runtime inspection showing no context survives connection return.

Known boundary: the local Compose database user owns the schemas, so PostgreSQL owner bypass remains available to controlled migrations/workers. Production deployment must use a non-owner runtime role and/or `FORCE ROW LEVEL SECURITY`, with worker scope propagation, before calling database isolation certification complete. The runtime wrapper still clears the settings before returning pooled connections.
