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

The real PostgreSQL integration test creates one central database and two
physically separate Tenant databases, applies the tenant metadata migrations,
and checks central authority revalidation, routing and pool reuse,
wrong-physical-database rejection, missing or mismatched Workspace-anchor
denial, runtime read-only access to the anchor, RLS scope application and
cleanup, pool capacity failure, idle eviction, safe rebinding with an active
transaction, and same-thread transaction fences. An explicitly named
test-only fixture rehearsal loads the reviewed catalog JSON and family/SKU
mapping into temporary probe tables in both Tenant databases and checks the
existing deterministic reference IDs and curated/provisional counts. The
probe tables are dropped after the assertion. This verifies fixture mapping
and physical separation only; it is not extraction/reconciliation of business
data, a full Catalog schema migration, or an automatic seed for a normal Tenant.
These tests do not prove deployed secret-provider behavior, production
provisioning, operational recovery or production readiness.

Remaining integration work includes an operational provisioning and
reconciliation process using an authorized source snapshot, production
singleton and capacity configuration, explicit Tenant routing for background
jobs, and additive treatment of the existing business-to-central foreign keys
before any data extraction. The test fixture is not an authorized source
snapshot. The current global Spring DataSource, existing adapters, and
published central Flyway history through V146 remain unchanged. No existing
API business command uses this router yet; no HTTP cutover is implemented.
