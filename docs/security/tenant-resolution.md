# Tenant Resolution and Access Security

## Current contract

Tenant authority is resolved from an authenticated user identity plus an authoritative, verified membership lookup. Client headers, route values and JWT claims may select a requested scope for resolution, but they are not proof of membership and must never become the source of tenant authority.

The application contract is:

```text
resolve(userId, requestedTenantId, requestedWorkspaceId, requestedSurface)
  -> verified membership + active tenant/workspace + canonical role policy
  -> CurrentAccessContext
```

`VerifiedMembershipResolutionPort` must scope its lookup by `UserId`, `TenantId` and `WorkspaceId`. An adapter must revalidate the membership and current tenant/workspace statuses on every resolution boundary; cached or stale client claims are insufficient.

## Cross-tenant and cross-workspace policy

- A returned membership whose user, tenant or workspace differs from the requested identities is denied.
- A resource outside `CurrentAccessContext.tenantId()` is denied by `requireTenant(...)`.
- A resource outside `CurrentAccessContext.workspaceId()` is denied by `requireWorkspace(...)`.
- A request for another surface is denied by `requireSurface(...)`.
- A role that is not mapped to the requested surface is denied by `RoleSurfacePolicy`.
- A permission not in the canonical `PermissionPolicy` set for the membership role is denied.

These checks are equality checks against typed UUID value objects. Slugs are human-facing lookup values; they are not an authorization bypass and do not replace tenant/workspace identity checks.

## Inaccessible tenant policy

Missing, mismatched or inactive tenant/workspace membership is intentionally indistinguishable at the application boundary. `ResolveCurrentAccessContextService` raises the generic `InaccessibleTenantException` with no tenant existence, membership or status detail. This prevents cross-tenant enumeration and avoids leaking whether a requested tenant or workspace exists.

Surface or permission failures are policy denials (`AccessPolicyViolation`) and must be mapped by a future Presentation adapter to the approved API error contract. This task does not add an HTTP mapping or change the current IAM implementation.

## Integration requirements for future adapters

Future JWT/Spring adapters may translate external claims into `UserId` and requested scope, but JWT parsing remains Infrastructure/IAM responsibility. Future controllers must resolve `CurrentAccessContext` before invoking tenant-owned use cases. Domain and application code must remain free of Spring, JPA, JWT, SQL and transport imports.
