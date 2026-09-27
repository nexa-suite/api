# BC-01 — Tenant & Access Governance

- **Owner:** `com.nexa.api.tenantaccessgovernance`
- **Storage:** `iam`, `tenant_management`
- **Owns:** tenant, workspace, identity, membership, roles, permissions,
  authentication and authorization context.
- **Public contracts:** current access context, permission checks and identity
 /session boundaries used by the API edge.
- **v0.15 relation:** authorizes fulfillment/logistics commands and supplies the
  actor and tenant/workspace scope; it does not own business fulfillment state.
- Tenant settings may expose a compatibility notification-preferences facade.
  Its DTOs carry values only; BC-10 owns preference categories, channels,
  defaults and validation through runtime composition.
- **Excludes:** customer relationships, payment provider state and Security
  Audit's distinction from BC-11 Business Traceability.

Canonical source of names and domain boundaries: [module map](../../bounded-context-module-map.md).
