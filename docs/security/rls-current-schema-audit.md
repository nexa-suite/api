# Current-schema RLS audit — V107

Status: **inventory and direct-scope closure verified on a fresh PostgreSQL
18.4 schema**. This evidence classifies the API schema; it does not certify
production isolation or imply that every application table has its own RLS
policy.

The [V107 table inventory](./rls-table-inventory-v107.tsv) classifies every
one of the 171 application tables produced by the current migration line.
`ModernPostgresRlsClosureMigrationTests` compares the inventory against live
PostgreSQL table names, scope columns, and RLS flags after applying the current
migrations from an empty schema. The result has 135 directly Tenant/Workspace
scoped tables, all with RLS enabled and forced; there are no unclassified
tables and no `APPROVED_EXCEPTION` rows.

| Classification | Tables | RLS enabled and forced |
| --- | ---: | ---: |
| `FORCED_RLS_DIRECT_SCOPE` | 135 | 135 |
| `INHERITED_SCOPE_JUSTIFIED` | 11 | 0 |
| `GLOBAL_REFERENCE` | 5 | 0 |
| `GLOBAL_IDENTITY_SECURITY` | 7 | 0 |
| `TECHNICAL_GLOBAL` | 7 | 0 |
| `CROSS_SCOPE_WORKER` | 6 | 0 |
| `APPROVED_EXCEPTION` | 0 | 0 |
| **Total** | **171** | **135** |

The fresh-schema test also verifies that every directly scoped table has an
explicit policy and that each two-key Tenant/Workspace policy has both
`USING` and `WITH CHECK` predicates. The V103 transfer-history, V104 material
change, and V107 pricing tables have individual scope evidence in
[`rls-direct-scope-evidence-v107.tsv`](./rls-direct-scope-evidence-v107.tsv).
Historical V100 and V102 inventories remain unchanged as point-in-time
evidence.

The reviewed pre-context policies remain narrowly capability-bound:

- Tenant and Workspace discovery uses a configured bootstrap slug, requested
  workspace slug, verified membership, authenticated identity, or an explicit
  transaction-local worker scan setting.
- Invitation acceptance reads one pending row by the presented opaque token
  hash; writes occur only after Tenant/Workspace resolution.
- Public registration access requires the row ID plus opaque status-token
  hash, or the authorized operator path naming one registration ID.
- Global role templates are readable; runtime role-definition writes are
  limited to Tenant/Workspace-scoped custom roles.

`RlsRuntimeDatabaseIsolationIT` passed 13 PostgreSQL integration tests for
scope isolation, lookup capabilities, transaction and pooled-connection
cleanup, worker scope, rollback, and runtime privileges. The restricted test
login is verified as non-superuser, non-`BYPASSRLS`, not the object owner, and
without database-creation or role-creation authority. V107 additionally
grants that role `SELECT` only on the new Price List, Price List Item, and
Customer Terms tables. These are test-environment results; deployed database
credentials and provider configuration still require environment-specific
verification.

The six `CROSS_SCOPE_WORKER` tables deliberately support technical queue
claiming. Their workers must resolve and apply each business effect under an
explicit Tenant/Workspace scope. Classification alone is not authorization;
worker behavior remains covered by the runtime isolation suite.
