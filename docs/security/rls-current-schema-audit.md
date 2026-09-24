# Current-schema RLS audit — V100

Status: technical inventory, **coverage open**. This audit does not approve an
exception or certify production isolation.

The [table inventory](./rls-table-inventory-v100.tsv) classifies all 165
application and Flyway tables produced by a clean PostgreSQL 18.4 migration
through V100. A separate read-only query against the local V100 database
matched the table and RLS counts. No existing database was modified for this
audit.

| Category | Meaning | Tables | With forced RLS | Without own RLS |
| --- | --- | ---: | ---: | ---: |
| A | Direct Tenant and Workspace business scope | 122 | 91 | 31 |
| B | Tenant scope, including Tenant and Workspace identity rows | 8 | 0 | 8 |
| C | Scope inherited through a parent or Workspace reference | 11 | 0 | 11 |
| D | Global reference or configuration | 5 | 0 | 5 |
| E | Global identity, security, or pre-context technical state | 13 | 0 | 13 |
| F | Cross-scope technical/provider queues | 6 | 0 | 6 |
| G | Other intentional exclusion | 0 | 0 | 0 |
| **Total** | | **165** | **91** | **74** |

All 91 protected tables have `ENABLE ROW LEVEL SECURITY`, `FORCE ROW LEVEL
SECURITY`, and at least one policy with `USING` and `WITH CHECK`. The local
`nexa_runtime` login is neither superuser nor `BYPASSRLS`, does not own the
application tables, and cannot create roles or databases. The `nexa` migrator
owns all 165 tables and is privileged; it is not an ordinary runtime login.
The integration suite's `RlsRuntimeDatabaseIsolationIT` exercises the
restricted role, missing/mismatched scope, and pooled-connection cleanup.

## Coverage decisions still needed

The 31 unprotected category A tables include 19 `catalog_management` tables,
`audit.event`, two Sales command/sequence tables, and nine Tenant Governance
tables. Categories B and C add 19 rows with Tenant or inherited Workspace
scope but no own policy. The inventory names every row. Parent constraints and
application predicates are useful controls; they do not turn an unprotected
table into RLS evidence.

Category F contains `integration` outbox/inbox/change queues,
`payments.stripe_event_inbox`,
`business_documents.document_generation_request`, and
`iam.security_notification_outbox`. Their workers need cross-scope claiming
followed by explicit scoped effects. Category E includes identity and
pre-context state; `iam.security_audit_event` also carries Tenant and Workspace
columns. These technical classifications require explicit Security/Data
review before any permanent RLS exclusion. Category D covers four immutable
reference tables and the permission definition registry.

An additive migration should follow a reviewed per-table policy and worker
access design, with non-owner runtime tests for read, write, absent scope,
cross-Tenant IDs, rollback, pool reuse, and stale worker claims. The current
Blueprint records complete RLS coverage proof as open. No policy or historical
migration was changed in this audit.
