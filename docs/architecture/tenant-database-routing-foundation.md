# Tenant physical-database routing foundation

Status: **selective local-profile wiring over an opt-in implementation
foundation; not complete request routing or production cutover**.

The central API `DataSource` still serves IAM/Tenant records and business
schemas, with request scope applied by the existing RLS wrapper. Selected
business use cases are routed to Tenant databases when the `local` profile and
the corresponding capability flags are enabled; this is not complete request
routing. The reviewed Render manifest declares profile `s3`, not `local`; that
is configuration evidence only and does not verify a live deployment or
production cutover/data move.
The accepted target separates a central physical database for IAM, Tenant
membership and suite governance from one provisioned business database per
Tenant.

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
The construction target for mutable business/master relations in the generated
BC02–BC11 scope is at least 3NF, using documented candidate keys and dependencies;
apply BCNF/4NF/5NF decomposition only where those dependencies justify lossless
reconstruction, and keep immutable evidence snapshots distinct from mutable
master data. Repeating immutable Tenant and Workspace keys on child rows is a
narrow scope-consistency exception where needed for PostgreSQL RLS policy
performance: `USING` and `WITH CHECK` must preserve owner/child agreement. This
changes neither the eleven Bounded Context ownerships nor Tenant as the maximum
data-isolation boundary, and does not permit cross-Tenant reads. This guidance
describes only the opt-in generated baseline, not a global schema-normalization
certification or production cutover.

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
Additive V18 grants the isolated `nexa_business_traceability_worker` SELECT on
only the snapshot's Tenant/Workspace, source-state/version, policy-days, and
revision columns. Forced RLS still scopes that read to the verified worker
Tenant/Workspace; the worker receives no snapshot write privilege. V18 also
grants the worker only the SELECT predicate columns and UPDATE release fields
on `warehouse.inventory_backing`, so approved-workflow expiry can release its
backing within the same Tenant callback.

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
cross-database atomicity is claimed. When explicitly invoked with a verified
access context, the V4 resolver refreshes a stale snapshot before executing
the supplied business work. Selected local-profile Purchase Request use cases
call the resolver; it is not wired across the global HTTP path and does not
authorize a Purchase Request write by itself, so no production Purchase
Request cutover is evidenced. Central V1–V146, including V83, remain unchanged.

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
closes after its last lease is released. The reviewed Render manifest selects
profile `s3`, so it does not activate route bindings guarded by `@Profile("local")`;
this does not verify the live runtime. The `local` profile has selected route
bindings, but their capacity and completeness for production traffic are not
established.

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

Additive V169 separates central provisioning-queue mutation from
`nexa_runtime`. Organization activation retains Tenant/Workspace-scoped SELECT
and INSERT in its existing transaction; V169 revokes known runtime UPDATE
grants on both queues and checks effective table-level UPDATE plus UPDATE on
the `status` column. The dedicated `nexa_tenant_database_provisioner` login can read queue
state and update only lifecycle columns, and RLS binds queue updates to that
database principal. An authorized database administrator must run the V169
preparation script before Flyway: it removes the worker's direct database grants across
connectable databases and its direct schema, table, sequence and routine
grants, then gives it a direct `CONNECT` on the central database and `USAGE`
on `tenant_management`. The script fails closed if it finds worker
default-privilege grants, which must be removed by their owners. It transfers
the Tenant binding, both provisioning queues, and `public.flyway_schema_history`
to `nexa_migrator`; temporarily grants then revokes `CREATE` on `public`; and
grants the migrator `USAGE`/`CREATE` on `tenant_management` plus only the
Workspace and Membership column reads required by existing RLS policies.
V169 requires a direct Flyway session as `nexa_migrator`, checks the role's
login and privilege flags, table ownership, narrow effective reads, and the
worker's effective data and security-definer function access, then applies
the object-specific queue and binding grants. `nexa_runtime` must be a
`LOGIN NOINHERIT` role with no role memberships in either direction;
`modern-up.sh` and the migration reject inherited role membership rather than
assuming that `NOINHERIT` alone prevents `SET ROLE`. The migration also checks
effective table-level UPDATE and UPDATE on the `status` column for both queues
after revoking runtime access. Local Compose provisions the runtime role as `NOINHERIT`, verifies it
has no memberships, and supplies database passwords to `psql` over standard
input instead of process arguments. Tenant/Workspace relationship
checks go through a fixed-search-path, security-definer helper that returns
only whether the exact IDs match the connection's explicitly set
Tenant/Workspace scope and an existing Workspace pair. The helper runs as
`nexa_migrator`, which must remain non-superuser and without `BYPASSRLS`; it
receives Workspace SELECT and only the Membership columns needed by
Workspace's existing RLS subqueries. These GUCs scope the query; they are not
an authorization credential. The provisioner has no direct read grant on
Tenant, Workspace or Workspace Membership, so it cannot enumerate membership
user IDs to satisfy those subqueries. Local Compose uses a separate password
for `nexa_migrator`: it bootstraps legacy migrations through V168 with the
local administrator, runs the V169 preflight, then recreates the API so Flyway
connects as `nexa_migrator`; the API runtime datasource remains
`nexa_runtime`.

V172 makes `logistics.buyer_delivery_tracking` use invoker privileges so its
forced base-table RLS applies to the API caller. Local startup brings Flyway to
V171, runs `ops/database/prepare-v172-buyer-tracking-view.sql` as the local
database administrator, then recreates the API so the restricted migrator can
apply V172. This view transfer does not grant the migrator base-table reads.

The internal-console paths and worker Workspace scans retain cross-scope GUC
risks. HTTP filters authenticate operator UUID/token pairs before adapters set
`app.current_internal_operator_id`, but V154/V155 policies validate only UUID
shape. SQL as `nexa_runtime` can set an arbitrary UUID and read cross-Tenant
provisioning queues, Workspace anchors, database bindings, registrations, and
support request/audit data. No HTTP exploit was established; database policy
cannot independently verify the allowlist token. Closing this defense-in-depth
gap requires database-verifiable operator authority or separately credentialed
read paths. Separately, runtime can set `app.cross_scope_workspace_scan` and
read Workspaces across Tenants through V102; the setting remains needed by
current worker enumeration. V169 removes the scan-based provisioning queue
policy only, not the V102 Workspace path or operator-ID policies. Worker scope
reads therefore need a separately authenticated database identity before the
GUC can be removed. This is least-privilege construction evidence for the
opt-in local worker; the reviewed Render manifest declares one `web` service
and does not declare this worker. That does not establish what is running in
the live environment or authorize production provisioning or cutover.

The local V169 migration test creates an ordinary role with queue UPDATE,
grants it to `nexa_runtime`, and verifies that migration fails closed before
removing the membership and verifying the least-privilege grants. This is
disposable PostgreSQL evidence only. The current audit does not inspect grants
or `CONNECT` inherited from `PUBLIC` in deployed clusters; treat that as a
deployment hardening verification item, not as a confirmed API exploit.

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

Remaining integration work includes production provisioning and resolution of
the dedicated writer credentials, extending the selective local route bindings
to the remaining authorized commands, queries and background jobs, production
singleton and capacity configuration, provisioner-role creation and secret
delivery for any future non-local worker, and a verified Tenant-aware HTTP
cutover before production use. The test fixture is not an authorized source
snapshot. The central Spring `DataSource` remains for IAM/Tenant records and
unrouted business paths; selected local-profile adapters use the router. The
published central Flyway history through V146 remains unchanged.
