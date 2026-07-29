# ADR-007: Verified workspace resolution

Status: Accepted

## Context

Nexa is multi-tenant. Client-supplied tenant or workspace identifiers cannot establish authorization. Workspace membership and active tenant state must remain authoritative.

## Decision

Resolve active scope from verified JWT claims plus the active Workspace Membership loaded from PostgreSQL. Validate user, membership, workspace, tenant, role and surface consistency on each authenticated request. Mismatch returns `403` with `ACCESS_CONTEXT_INVALID`. Ignore or reject arbitrary `X-Tenant-Id`, `X-Workspace-Id` and query scope selectors.

## Alternatives considered

- Trust `X-Tenant-Id` or query parameters: rejected because a browser could select another tenant.
- Trust JWT claims without database lookup: rejected because membership can be revoked after token issuance.
- Resolve tenant from User Account: rejected because roles and workspace scope belong to Membership.

## Consequences

Every protected use case depends on `CurrentAccessContext`, not Spring Security directly. Request-level and application-level authorization both remain required.

## Risks

Stale claims, cache mistakes or missing membership locks can produce false grants. Resource not-found policy must remain consistent with the API contract.

## Evidence required

Cross-tenant integration tests, inactive membership/workspace/tenant tests, arbitrary-header tests, and authenticated browser flows.

## Review trigger

Review when workspace switching, delegated access, resource ownership rules or configurable tenant policies are introduced.
