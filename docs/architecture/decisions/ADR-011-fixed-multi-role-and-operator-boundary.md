# ADR-011 — Fixed multi-role memberships and system activation boundary

## Decision

Internal membership roles are stored in `tenant_management.membership_role_assignment` and are limited to the fixed role enum. The legacy single role is retained only as the membership type discriminator after V23; application authorization reads the assignment table. Buyer memberships remain external and never receive rows in the internal assignment table.

Organization activation is a separate system-level operation. Tenant roles cannot activate or reject registrations. Local end-to-end validation may provide `NEXA_SYSTEM_OPERATOR_TOKEN` through ignored environment configuration; production has no seeded operator credential.

## Consequences

Effective permissions are the union of fixed roles. Founder activation creates `TENANT_ADMIN` and `COMPANY_OWNER` in one transaction. The final active Tenant Admin cannot be removed or suspended. The operator UI is deferred.

## Compatibility boundary

The current baseline contains cross-package references while the modules are being hardened. Top-level Spring Modulith packages are intentionally marked open for this first gate and ArchUnit enforces the layer rules. A later bounded-context extraction can narrow these APIs without changing the external IAM contract.
