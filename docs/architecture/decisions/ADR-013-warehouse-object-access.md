# ADR-013: Explicit Warehouse object access

Status: accepted policy; implementation and verification pending

Decision date: 2026-09-30

## Context

MOB-US-015 requires that an operator permitted to Warehouse A cannot obtain lot
or quantity facts from Warehouse B in the same Workspace. Existing workspace
permissions and Tenant/Workspace isolation alone cannot establish this boundary.
The accepted BC-01 model does not define Warehouse-specific assignments. The
Product Owner approved the explicit grant policy below before implementation.

## Decision

An explicit grant binds one active Workforce Membership to one Warehouse in the
same Tenant and Workspace. Absence of a grant denies Warehouse object access.
The grant is necessary but never sufficient: every operation must also satisfy
the current permission, active Tenant/Workspace/Membership and resource scope.
No role, permission hint or client-selected Warehouse creates an implicit grant.

Grant administration requires the existing `tenant.role.assign` authority.
Assignment and revocation must use current server-verified authority and scoped
targets. A grant cannot confer administrative or operational permissions that
the Membership does not otherwise possess. Revocation must affect subsequent
server decisions; cached Mobile facts cannot authorize physical work.

BC-01 owns access assignment and evaluation. BC-05 owns Warehouse and inventory
facts. Their collaboration uses explicit owner contracts; consumers must not
read foreign business tables or move inventory authority into access governance.
Tenant/Workspace RLS remains defense in depth alongside application decisions.

## Required verification

- Warehouse A permitted and Warehouse B denied within one Workspace.
- No grant, revoked grant, suspended Membership and missing current permission
  deny facts and mutations.
- Other Tenant/Workspace cannot receive or use a grant.
- Lists, detail, identifier resolution and dependent physical operations apply
  the object boundary before exposing lot or quantity facts.
- Grant changes preserve current authorization version/concurrency guards and
  factual security/trace records.

This decision establishes policy, not completed capability, Product Acceptance
or Production Readiness. Existing fixtures must receive explicit grants where
their scenario requires Warehouse access; broad legacy roles cannot bypass the
new boundary.

## Warehouse-scoped availability projection

`GET /api/v1/warehouses/{warehouseId}/inventory-availability` exposes the existing availability quantities for one authorized, active Warehouse. It requires `warehouse.read` and the current WorkforceMembership grant before querying inventory. Both physical and sellable sums are filtered by Warehouse; the existing expiry, disposition, temperature, service, safety stock and active backing rules are unchanged. Warehouse-authorized callers of the existing aggregate endpoint also receive only quantities from their active grants, including when they have Catalog permissions. Catalog-only commercial callers retain their existing projection. Missing or inaccessible Warehouses return the canonical not-found response.

The V108 security inventories add the grant table while preserving the historical V107 artifacts. Integration fixtures assign explicit grants and obtain refreshed credentials after authorization-version changes; they do not introduce runtime defaults. Focused real PostgreSQL coverage checks the granted Warehouse quantity, denial of another Warehouse in the same Workspace, and invalid query handling.
