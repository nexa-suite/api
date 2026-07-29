# ADR-005: Deny-by-default API security

Status: Accepted for API `v0.5.0`

## Context

Every reachable business endpoint must have authenticated, active membership and explicit authorization evidence. A reachable business endpoint must not become an accidental anonymous or cross-tenant bypass.

## Decision

- Health and info actuator endpoints are public.
- Local OpenAPI documentation is public only under the `local` profile.
- Every `/api/**` route and every other unspecified route is denied by default.
- Anonymous business access returns `401` Problem Details.
- Authenticated access without an approved authorization rule returns `403` Problem Details.
- Form login, HTTP Basic, CSRF state, logout and sessions are disabled for this stateless API foundation.
- After JWT verification, every bearer request is revalidated against the active user, tenant, workspace and membership rows; DB-derived permissions replace token permissions for the request.

## Consequences

The catalog use case remains independently testable with filters disabled in unit/presentation tests. Runtime catalog access requires the verified membership filter and `catalog:read` authority; catalog content is still shared local reference data.
