# Tenant physical-database routing foundation

Status: **opt-in implementation foundation; not integrated into request or
business-adapter routing**.

The current API runtime uses one configured PostgreSQL `DataSource` for the
central IAM/Tenant records and business schemas, with request scope applied by
the existing RLS wrapper. This is AS-IS implementation evidence. The accepted
target separates a central physical database for IAM, Tenant membership and
suite governance from one provisioned business database per Tenant. The new
classes and migrations do not change the current request path or move data.

The additive V146 migration records a Tenant's physical database UUID,
credential-secret reference and lifecycle state in
`tenant_management.tenant_business_database_binding`. The separate
`db/tenant-migration` location writes the same Tenant UUID and physical
database UUID into that database's identity table. V2 adds a local
`tenant_workspace_scope_anchor` containing distinct Tenant and Workspace UUID
columns, with a local foreign key to the V1 database identity. It stores no
names, membership, roles, capabilities, credentials or central foreign keys.
The runtime role can read the anchor but cannot write it; migration/provisioning
credentials own those writes. The anchor only confirms that a centrally
validated Tenant/Workspace pair belongs in this physical database; it does not
grant access or replace central membership and authorization checks.

V3 is an additive Tenant business schema baseline generated from a fresh
central database at published Flyway V146. Its checked selector contains the
canonical BC02–BC11 tables, the 16 approved Tenant-local shared technical
tables, and four immutable `GLOBAL_REFERENCE` projections. It excludes BC01
IAM, Tenant, membership, role and credential tables. Tenant FKs target the V1
local identity; composite Tenant/Workspace FKs target the V2 anchor. The BC10
notification-preference table stores only a Workspace UUID, so its existing
unary FK targets the anchor's unique `workspace_id`. FKs to central membership
and user identity are removed while their business UUID columns and indexes
remain. Business-to-business FKs, checks, indexes, triggers, functions, RLS
policies and source `nexa_runtime` table grants are carried into the baseline.
Generation fails on an external FK outside these explicit mappings. The four
reference tables are loaded as read-only local projections; their logical
values and counts are checked against the published central migration state.
V3 creates schema only and contains no normal-Tenant business seed.

V3 preserves V83's `sales.assign_purchase_request_expiry()` definition, whose
central implementation reads
`tenant_management.operational_settings.purchase_request_expiry_days`. That
BC01 table remains excluded from Tenant databases. Additive local migration V4
replaces the copied trigger with a fail-closed projection reader. Its
`nexa_platform.purchase_request_expiry_policy_snapshot` is empty after
migration; it stores no default or normal-Tenant business seed. Each row carries
the central source state and version, policy days, and a Tenant-local monotonic
snapshot revision. The composite Tenant/Workspace key references the local
scope anchor and is protected by RLS. `nexa_runtime` receives SELECT only. The
separate `nexa_policy_snapshot_writer` login receives SELECT on the identity,
anchor and snapshot plus INSERT/UPDATE on the snapshot; its credentials must be
resolved separately from the runtime secret reference.

BC01's public `OperationalSettingsAccess` query validates the central
Tenant/Workspace pair without creating settings defaults. A present row returns
its accepted 1–7 day value and row version. A valid Workspace with no settings
row returns `CONFIRMED_ABSENT`, for which the accepted three-day fallback is
projected; an unknown or mismatched Workspace returns no source and fails
closed. If a source row appears after an absence snapshot, the local revision
advances even when the new central row starts at version zero. A stale central
version or concurrent local revision fails closed.

The opt-in `TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver` reads
the BC01 source before a routed operation, reconciles the local snapshot through
the distinct writer connection in its own Tenant-database transaction, then
sets the matching snapshot revision with `SET LOCAL` before the business
transaction. The V4 trigger requires that expected revision for each non-DRAFT
Purchase Request insert or relevant update, checks it against the scoped local
snapshot, and uses the snapshot days only when `expires_at` is missing. Missing
or stale revision fails closed. Connection scope cleanup also clears this GUC.
These are separate central-read, snapshot-write and business transactions; no
cross-database atomicity is claimed. The V4 resolver is not wired into the
global HTTP path or existing adapters, so no production Purchase Request
cutover is implemented. Central V1–V146, including V83, remain unchanged.

The central row has the determinant `tenant_id -> database_identity,
credential_secret_reference, lifecycle_state, created_at, updated_at, version`;
`tenant_id` is its primary key and the database identity and secret reference
are unique candidate keys. The physical identity relation has a singleton
key and unique Tenant and database UUIDs. The workspace anchor has unique
Tenant and Workspace identifiers and a local foreign key to the physical
identity. These relations are in BCNF under their declared constraints. They
have no independent multivalued dependency or nontrivial join dependency, so
4NF and 5NF do not add a separate decomposition requirement here. This
conclusion applies only to these three metadata relations, not to the existing
business schema.

The router requires a `CurrentAccessContext`, re-resolves the current
membership and authorization through the central access-context contract,
then reads only a `READY` registry binding. The business credential provider
receives the referenced secret key and does not receive central database
credentials. Each business connection checkout verifies that the connected
database contains exactly one identity row matching both the registry Tenant
UUID and physical database UUID. Before application work can obtain a
connection, the router also requires exactly one local Workspace anchor matching
the Tenant and Workspace IDs from that centrally revalidated context. Missing
or changed authority, missing READY binding, physical identity mismatch, or a
missing/mismatched Workspace anchor fails closed without using a fallback
database. Pool reuse is keyed by Tenant, physical database identity and
credential reference. One registry enforces an explicit maximum pool count
across all Tenant bindings in that router instance. It evicts the least
recently used idle pool when needed and fails closed when every slot is held
by an active transaction. A changed binding retires the previous pool so no
new lease uses it; the in-flight transaction may finish, and the retired pool
closes after its last lease is released. The application has no production
router wiring yet, so it has no deployment-level capacity value or singleton
guarantee.

Each routed operation is one local transaction on one Tenant business
database. The router rejects entry from an existing transaction and rejects
nested routed transactions. It invokes the callback inline on the calling
thread and rejects direct JDBC, DataSource, JdbcTemplate, stream and future
results. Callers must not schedule work asynchronously or retain the supplied
JdbcTemplate or JDBC resources after the callback returns. The central RLS
DataSource fence is a same-thread guard: it rejects central checkout on the
routed thread, but it does not propagate to other threads or prevent captured
work from escaping. This foundation provides no cross-database atomic commit.
Callers must keep central authority checks before the business transaction
and must not put central writes in the business callback.

After the physical identity check succeeds, the routed DataSource installs
Tenant and Workspace RLS settings from the access context revalidated through
central authority, independent of the request-local scope. It clears those
settings when a pooled connection is returned. The database integration test
exercises a `FORCE ROW LEVEL SECURITY` policy on each of the two business
databases and checks that pool state is empty after routed work.

The real PostgreSQL router integration test creates one central database and
two physically separate Tenant databases, applies the tenant metadata
migrations, and checks central authority revalidation, routing and pool reuse,
wrong-physical-database rejection, missing or mismatched Workspace-anchor
denial, runtime read-only access to the anchor, RLS scope application and
cleanup, pool capacity failure, idle eviction, safe rebinding with an active
transaction, and same-thread transaction fences.

The separate V3 integration test applies V1–V3 to two fresh Tenant databases.
It compares table, constraint, index, trigger, function, RLS policy and runtime
grant evidence against the central V146 schema while checking that central
identity and membership tables are absent. It then uses the reviewed 102-item
catalog seed and explicit family/SKU mapping as a disposable central fixture,
copies the scoped Catalog rows into one Tenant database, and verifies ordered
table counts and row checksums, preserved Product/SellableSku UUIDs, and the
50 curated plus 52 provisional visibility counts. The other Tenant remains
unseeded. This is a fixture extraction/reconciliation rehearsal only; it is
not an authorized production snapshot, an automatic seed for a normal Tenant,
or a production data migration. Neither test proves deployed secret-provider
behavior, production provisioning, operational recovery or production
readiness.

The opt-in V4 PostgreSQL integration rehearsal checks the central source query,
expiry values 1–7, the confirmed-absence three-day fallback, an absent-to-present
central row transition, local revision advancement, stale and missing expected
revision denial, distinct writer/runtime grants, and isolation across two
physical Tenant databases. It proves the local migration and manually wired
resolver under test credentials only; it does not prove production writer
credential provisioning or API/HTTP integration.

Remaining integration work includes provisioning the dedicated writer role and
credentials, an authorized snapshot refresh mechanism, production singleton
and capacity configuration, Tenant-aware routing for background jobs, and
adapter-level wiring before any production cutover. The test fixture is not an
authorized source snapshot. The current global Spring DataSource, existing
adapters, and published central Flyway history through V146 remain unchanged.
No existing API business command uses this resolver or router yet; no HTTP
cutover is implemented.
