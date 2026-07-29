# ADR-005: Deny-by-default API security

Status: Accepted for API `v0.4.0`

## Context

Catalog query is implemented before identity and tenant membership are approved. A reachable business endpoint must not become an accidental anonymous or authenticated bypass.

## Decision

- Health and info actuator endpoints are public.
- Local OpenAPI documentation is public only under the `local` profile.
- Every `/api/**` route and every other unspecified route is denied by default.
- Anonymous business access returns `401` Problem Details.
- Authenticated access without an approved authorization rule returns `403` Problem Details.
- Form login, HTTP Basic, CSRF state, logout and sessions are disabled for this stateless API foundation.

## Consequences

The catalog use case is independently testable with filters disabled in unit/presentation tests, while runtime evidence remains explicitly blocked until identity, membership and tenant authorization are approved.
