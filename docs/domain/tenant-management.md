# Tenant Management Domain

## Purpose

Tenant Management owns the identity and access scope that ties a user to a tenant and workspace. It does not authenticate credentials, parse JWTs, persist memberships or expose REST endpoints in this foundation.

The bounded-context boundary is:

`User identity -> Membership -> Tenant + Workspace scope -> Surface + Permission policy`

Membership owns the canonical business role. IAM may prove who the user is, but it does not define or override the membership role.

## Current value objects and statuses

- `TenantId`, `WorkspaceId`, `MembershipId` and `UserId` are UUID identity value objects. Canonical UUID text is accepted; null, blank and non-canonical values are rejected.
- `TenantName` and `WorkspaceName` trim input, collapse whitespace, preserve human-readable capitalization and use NFC normalization. Maximum length is 160 characters.
- `TenantSlug` and `WorkspaceSlug` normalize accents away, lowercase ASCII input, turn separator runs into one hyphen, trim edge hyphens and require 3–63 characters. Slugs are safe lookup keys, not display names.
- `TenantStatus` and `WorkspaceStatus` are `ACTIVE`, `SUSPENDED` and `PENDING_REVIEW`. Only `ACTIVE` is accessible.
- `MembershipStatus` is `ACTIVE`, `INVITED` or `DISABLED`. Only `ACTIVE` is usable for an access context.

`Tenant` and `Workspace` are immutable domain records. A workspace carries its owning `TenantId`; a membership carries the user, tenant and workspace identities.

## Canonical roles and surfaces

`MembershipRole` is the only role vocabulary in this context:

| Membership role | Surface | Ownership intent |
|---|---|---|
| `COMPANY_OWNER` | `PLATFORM` | Company, workspace, teammate and policy administration |
| `SALES` | `PLATFORM` | Catalog, sales, orders and commercial documents |
| `WAREHOUSE` | `PLATFORM` | Warehouse and inventory operations |
| `LOGISTICS` | `PLATFORM` | Warehouse visibility, dispatch, shipments and logistics |
| `BUYER` | `PORTAL` | Buyer portal and purchase requests |

`RoleSurfacePolicy` is deny-by-default. A role cannot select a different surface by changing a request field or token claim.

## Canonical permission policy

`PermissionPolicy` is the single role-to-permission mapping. It exposes immutable sets and does not accept caller-supplied permissions.

| Role | Required permissions |
|---|---|
| `COMPANY_OWNER` | All declared permissions: tenant/workspace/membership administration, catalog, sales, orders, documents, warehouse, inventory, logistics, shipments, dispatch, portal, requests and analytics |
| `SALES` | `CATALOG_READ`, `CATALOG_WRITE`, `SALES_READ`, `SALES_WRITE`, `ORDERS_READ`, `ORDERS_WRITE`, `DOCUMENTS_READ`, `DOCUMENTS_WRITE` |
| `WAREHOUSE` | `WAREHOUSE_READ`, `WAREHOUSE_WRITE`, `INVENTORY_READ`, `INVENTORY_WRITE` |
| `LOGISTICS` | `WAREHOUSE_READ`, `LOGISTICS_READ`, `LOGISTICS_WRITE`, `SHIPMENTS_READ`, `SHIPMENTS_WRITE`, `DISPATCH_READ`, `DISPATCH_WRITE` |
| `BUYER` | `PORTAL_READ`, `PORTAL_WRITE`, `REQUESTS_READ`, `REQUESTS_WRITE`, `DOCUMENTS_READ` |

Permission codes are explicit (`catalog:read`, `requests:write`, etc.). No wildcard permission is used in this foundation.

## Application boundary

`VerifiedMembershipResolutionPort` is the outbound application port. Its adapter receives the authenticated `UserId` plus requested `TenantId` and `WorkspaceId`, then returns an optional `VerifiedMembership` snapshot containing the membership, tenant status and workspace status.

`ResolveCurrentAccessContextService` creates `CurrentAccessContext` only when:

1. the port returns a result;
2. the returned membership matches all requested user, tenant and workspace identities;
3. membership, tenant and workspace are active; and
4. the role is allowed on the requested `Surface`.

Downstream application use cases receive `AccessContext`/`CurrentAccessContext` and must call its scope and permission guards before reading or changing tenant-owned resources.

## Deliberately excluded

No Spring, JPA, JWT, repository adapter, database migration, controller, DTO, IAM implementation or tenant-management endpoint belongs in this task. Those integrations must consume these contracts from Infrastructure or Presentation without moving policy into transport code.
