# Current-schema RLS audit — V106 candidate

Status: source-derived V106 inventory and evidence register. The V106 fresh
schema, v0.17.1 upgrade, and restricted-runtime gates have not been verified in
this reconciled checkout as part of this audit. This document does not certify
production isolation; the deployed runtime role remains unverified.

The [V106 table inventory](./rls-table-inventory-v106.tsv) classifies the
expected 169 physical application tables, including
`public.flyway_schema_history`. The inventory has 170 lines including its
header. Its category counts are source-derived and must match the live schema
in `ModernPostgresRlsClosureMigrationTests.freshSchemaMatchesEveryTableClassificationAndV106Policy`:

| Category | Tables | Forced RLS | Own policy absent |
| --- | ---: | ---: | ---: |
| Forced direct Tenant/Workspace scope | 133 | 133 | 0 |
| Inherited scope justified by parent/reference | 11 | 0 | 11 |
| Global reference | 5 | 0 | 5 |
| Global identity/security | 7 | 0 | 7 |
| Technical global | 7 | 0 | 7 |
| Cross-scope worker queues | 6 | 0 | 6 |
| Approved exception | 0 | 0 | 0 |
| **Total current tables** | **169** | **133** | **36** |

Of the 133 direct-scope tables, 125 have both `tenant_id` and `workspace_id`;
six are Tenant-only, the Tenant root is keyed by its own `id`, and Workspace
identity is Tenant-owned and keyed by `id`. The inventory assigns every table
exactly one category. Every direct-scope row must have enabled and forced RLS
and a policy with both `USING` and `WITH CHECK`. The
[V106 direct-scope evidence register](./rls-direct-scope-evidence-v106.tsv)
records the scope source, read/write paths, workers, policy shape, and
table-specific evidence for all three tables added after V102.

## Changes after the historical V102 inventory

The V100 and V102 TSV snapshots and the V102 evidence register are retained as
historical migration evidence; they do not describe the V106 schema. V103 adds
`warehouse.inventory_transfer_history`, a directly scoped append-only history
table. V104 adds
`sales.purchase_request_material_change`, a directly scoped proposal record
with runtime `SELECT`, `INSERT`, and `UPDATE` privileges and no `DELETE` grant.
V105 adds a scoped replacement-document foreign key and index but no table.
V106 adds `warehouse.stock_temperature_evidence`, a directly scoped,
append-only, pending-only table with runtime `SELECT` and `INSERT` privileges.
The current candidate V104 grant is guarded when `nexa_runtime` is absent;
V1–V105 migration files remain historical and are not rewritten by this audit.

The V102 direct-scope baseline was 130 tables. These three post-V102 tables
raise the expected V106 inventory to 133. No category is treated as an
approved RLS exception. The remaining 36 tables are specifically classified
as inherited, global reference/identity, technical global, or cross-scope
worker records; their classification does not imply that they are public or
that application authorization is unnecessary.

`iam.access_context_selection_ticket` remains a global identity/security
classification: it stores only a hashed, short-lived selection ticket, its
user and surface, and consumed/revoked timestamps. IAM validates the opaque
ticket before using its persisted identity. This classification is not a
claim that the table is public.

## Verification gates and limits

- Fresh-schema verification must compare every live table, scope-column fact,
  RLS flag, policy expression, and runtime privilege with the V106 inventory.
- Upgrade verification must start from the exact published `v0.17.1` V100
  migration baseline, apply the current migrations through V106, and retain
  historical rows.
- Restricted-runtime verification must use the disposable `nexa_runtime`
  login without role switching; it must prove missing and mismatched scopes
  fail closed, including writes to the V104 material-change table.
- CI must fetch the `v0.17.1` tag before Maven verification for the upgrade
  test.
- Production certification still requires verifying that the deployed runtime
  role is not superuser, `BYPASSRLS`, a table owner, or the migration principal.

The older [scope classification review](./rls-scope-classification.md) records
v0.16.1-era decisions only and is superseded for current coverage by this V106
candidate inventory.
