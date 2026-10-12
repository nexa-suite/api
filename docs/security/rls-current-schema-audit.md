# Current-schema RLS audit — V161

Status: **the fresh-schema migration gate passed (1/1 test) against PostgreSQL
18.4**. `ModernPostgresMigrationTests` checks the migrated tables against the
current inventory, including scope columns, RLS flags, and direct-scope policy
requirements. This records schema evidence; it does not certify deployed
isolation, production readiness, or per-Tenant physical routing.

The [V161 table inventory](./rls-table-inventory-v161.tsv) classifies all 217
application tables produced by the current migration line. Compared with
V146, it adds ten tables: nine forced direct-scope tables and one central
callback-route table. The migration test compares the complete table set and
scope/RLS metadata against this inventory. There are no unclassified tables
and no `APPROVED_EXCEPTION` rows.

| Classification | Tables | RLS enabled and forced |
| --- | ---: | ---: |
| `FORCED_RLS_DIRECT_SCOPE` | 180 | 180 |
| `INHERITED_SCOPE_JUSTIFIED` | 11 | 0 |
| `GLOBAL_REFERENCE` | 5 | 0 |
| `GLOBAL_IDENTITY_SECURITY` | 7 | 0 |
| `TECHNICAL_GLOBAL` | 7 | 0 |
| `CROSS_SCOPE_WORKER` | 7 | 0 |
| `APPROVED_EXCEPTION` | 0 | 0 |
| **Total** | **217** | **180** |

The V146 per-table evidence in
[`rls-direct-scope-evidence-v146.tsv`](./rls-direct-scope-evidence-v146.tsv)
remains a point-in-time record. The V161 migration gate checks the current
schema and direct-scope policy requirements against the V161 inventory.
Historical V100, V102, and V146 inventories remain unchanged as point-in-time
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

V146 added the central `tenant_management.tenant_business_database_binding`
registry table. It has direct Tenant-only RLS and grants the runtime role
`SELECT` only. This metadata table does not route current business adapters.
The current API runtime still uses one configured PostgreSQL `DataSource` for
central and business schemas with request-scoped RLS. The accepted target of a
central governance database plus one physical business database per Tenant is
separate from this AS-IS RLS inventory; see the
[Tenant physical-database routing foundation](../architecture/tenant-database-routing-foundation.md).

Six `CROSS_SCOPE_WORKER` tables support technical queue claiming. Their
workers must resolve and apply each business effect under explicit
Tenant/Workspace scope. The seventh, `tenant_management.payment_provider_route`,
stores central callback-routing metadata addressed by an opaque route
reference. Classification alone is not authorization.
