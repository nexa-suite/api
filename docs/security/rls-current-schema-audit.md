# Current-schema RLS audit — V146

Status: **current inventory and direct-scope closure are verified against a
fresh PostgreSQL 18.4 schema**. This classifies API schema evidence; it does not
certify deployed isolation, production readiness, or per-Tenant physical
routing.

The [V146 table inventory](./rls-table-inventory-v146.tsv) classifies all 207
application tables produced by the current migration line. The fresh-schema
checks in `ModernPostgresRlsClosureMigrationTests` compare table names, scope
columns, RLS flags and direct-scope policies after applying the migrations.
There are no unclassified tables and no `APPROVED_EXCEPTION` rows.

| Classification | Tables | RLS enabled and forced |
| --- | ---: | ---: |
| `FORCED_RLS_DIRECT_SCOPE` | 171 | 171 |
| `INHERITED_SCOPE_JUSTIFIED` | 11 | 0 |
| `GLOBAL_REFERENCE` | 5 | 0 |
| `GLOBAL_IDENTITY_SECURITY` | 7 | 0 |
| `TECHNICAL_GLOBAL` | 7 | 0 |
| `CROSS_SCOPE_WORKER` | 6 | 0 |
| `APPROVED_EXCEPTION` | 0 | 0 |
| **Total** | **207** | **171** |

The same test verifies the current direct-scope evidence in
[`rls-direct-scope-evidence-v146.tsv`](./rls-direct-scope-evidence-v146.tsv).
Historical V100 and V102 inventories remain unchanged as point-in-time
evidence.

The reviewed pre-context policies remain capability-bound:

- Tenant and Workspace discovery uses a configured bootstrap slug, requested
  workspace slug, verified membership, authenticated identity, or an explicit
  transaction-local worker scan setting.
- Invitation acceptance reads one pending row by the presented opaque token
  hash; writes occur after Tenant/Workspace resolution.
- Public registration access requires the row ID plus opaque status-token
  hash, or the authorized operator path naming one registration ID.
- Global role templates are readable; runtime role-definition writes are
  limited to Tenant/Workspace-scoped custom roles.

V146 adds the central `tenant_management.tenant_business_database_binding`
registry table. It has direct Tenant-only RLS and grants the runtime role
`SELECT` only. This metadata table does not route current business adapters.
The current API runtime still uses one configured PostgreSQL `DataSource` for
central and business schemas with request-scoped RLS. The accepted target of a
central governance database plus one physical business database per Tenant is
separate from this AS-IS RLS inventory; see the
[Tenant physical-database routing foundation](../architecture/tenant-database-routing-foundation.md).

The six `CROSS_SCOPE_WORKER` tables support technical queue claiming. Their
workers must resolve and apply each business effect under explicit
Tenant/Workspace scope. Classification alone is not authorization.
