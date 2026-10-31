# API operations

The current local API runtime is the modern Compose stack in
[`compose/modern.compose.yml`](./compose/modern.compose.yml). Prepare the local
environment with `scripts/setup-local-environment.sh`, then use
`compose/scripts/modern-up.sh`, `modern-down.sh` and `status.sh`.
After updating an older checkout, run `scripts/generate-local-env.sh` once to
add the separate `nexa_runtime` and `nexa_migrator` credentials to
`.env.local`.

Legacy Compose files and scripts remain as comparison and compatibility
evidence. They are not the default runtime. `compare-up.sh` intentionally starts
both stacks; use `modern-up.sh` for the current API runtime.

See [Compose details](./compose/README.md) and the
[script inventory](../scripts/README.md). This navigation does not change
service topology or runtime behavior.

The opt-in per-Tenant local business database workflow is documented in
[Local Tenant business database provisioning](./tenant-business-database-local.md).

## Academic Render and Neon deployment

The Render service uses GitHub `main`, the Free plan and the existing Neon
`production` branch. Render receives the public database and Object Storage
identifiers from [`render.yaml`](../render.yaml); passwords and access keys
remain service secrets and are never stored in the repository.

The academic deployment sets `NEXA_CLAMAV_MODE=disabled` because no private
ClamAV service is available on the Free plan. This mode is fail-closed:
evidence that requires malware scanning is rejected with
`MALWARE_SCANNER_DISABLED`, remains unavailable and cannot be downloaded.
Generated business documents that do not require malware scanning continue to
use their existing flow. Network scanning remains the default outside this
explicit deployment configuration, and deterministic local scanning remains
restricted to local or test profiles.

### Neon V140 migration gate

Neon runs the API with separate `nexa_migrator` and `nexa_runtime` roles. Because
`tenant_management.role_definition` uses `FORCE ROW LEVEL SECURITY`, V140's
canonical global `business_operations_manager` row needs a temporary, exact
insert policy while Flyway runs. The policy does not grant a privilege to the
runtime role and does not permit any other row.

Run the following sequence with direct connections authenticated as the named
role. Keep the password values outside the repository:

1. As `nexa_migrator`, execute [`database/prepare-v140-role-definition.sql`](./database/prepare-v140-role-definition.sql).
2. As `nexa_migrator`, run Flyway through the pending migrations.
3. In a `finally` or equivalent cleanup step, as `nexa_migrator`, execute [`database/cleanup-v140-role-definition.sql`](./database/cleanup-v140-role-definition.sql), whether Flyway succeeds or fails.
4. Verify that the temporary policy is absent and `FORCE ROW LEVEL SECURITY` is still enabled before deployment.

The preparation script stops if the table owner, enabled/forced RLS mode, or
migrator role flags are unsafe. It also stops when an earlier temporary policy
remains. The cleanup script is idempotent and removes only the expected
`nexa_v140_migrator_insert` policy.
Neither script changes ownership, grants `BYPASSRLS`, grants runtime write
access, or stores credentials. Do not deploy while the temporary policy remains.

### V169–V171 Tenant migration-role grants

For a fresh central database, before Flyway applies V169, run
[`database/prepare-v169-tenant-provisioner.sql`](./database/prepare-v169-tenant-provisioner.sql)
as an authorized database administrator with authority to revoke the worker's
grants and transfer the named objects to `nexa_migrator`. It stops before
changing grants if the worker appears in an owner-specific default-privilege
ACL; an authorized owner must revoke that default grant and rerun preparation.
It clears the worker's direct database grants across connectable databases and
direct schema, table, sequence, and routine grants. It grants the worker a
direct `CONNECT` on the central database and `USAGE` on `tenant_management`.
It transfers the Tenant binding, both provisioning queues, and
`public.flyway_schema_history` to `nexa_migrator`. For V170 it also transfers
the ten existing child tables that receive parent-scoped RLS policies to that
restricted migration role, which must own them to alter their RLS metadata.
For V171 it transfers `organization_registration` and
`membership_admin_event` so the restricted migration role can add their
composite scope constraints and preflight existing rows. It grants that role
`REFERENCES` only on `workspace(tenant_id, id)` and
`workspace_membership(workspace_id, id)`, the exact referenced keys required by
those constraints. These grants do not add privileges to `nexa_runtime`. The
V171 migration temporarily removes `FORCE ROW LEVEL SECURITY` from the two
owned tables in its transaction so the preflight sees all existing rows. For
each candidate it sets the existing Tenant/Workspace RLS context and validates
the Workspace pair through its existing row policy, then checks membership IDs
against their Workspace keys. It restores the caller's scope and `FORCE` before
it commits.
The role remains a direct-login, non-superuser, non-BYPASSRLS role without
membership; the application runtime role is not changed. The script temporarily
grants and then revokes `CREATE` on `public`, grants `USAGE` and `CREATE` on
`tenant_management`, and gives the role only the Workspace and Membership
column reads required by the scope helper and by Workspace's existing RLS
policies, plus the two column-level `REFERENCES` grants required by V171.
Flyway must connect directly as `nexa_migrator`. V169 checks that session,
role flags, object ownership, narrow Workspace/Membership reads, and the
worker's effective data and security-definer function access before applying
its queue and binding grants. The script keeps FORCE RLS intact. This
repository change does not execute it against a managed database.

### V172 Buyer tracking view isolation

After V171 and before Flyway applies V172, run
[`database/prepare-v172-buyer-tracking-view.sql`](./database/prepare-v172-buyer-tracking-view.sql)
as an authorized database administrator. It transfers only
`logistics.buyer_delivery_tracking` to `nexa_migrator`, temporarily grants
`CREATE` on `logistics` for that transfer, then revokes the grant. The
persistent `USAGE` grant lets Flyway resolve the view. The migrator receives no
`SELECT` on its dispatch or proof-of-delivery base tables. V172 sets
`security_invoker`, so runtime queries use `nexa_runtime` privileges and forced
RLS policies. Use this separate script for databases already at V171; rerunning
the V169 preparation would also normalize provisioner grants. Apply V172 under
the usual migration lock and deployment controls.

Local Compose bootstraps legacy migrations through V168 with the local
bootstrap connection and runs the V169 preparation as the database
administrator. It then brings Flyway to V171, runs the separate V172 view
preparation as the administrator, and recreates the API with the dedicated
`nexa_migrator` login to apply V172. The API's runtime datasource remains
`nexa_runtime`.

### V176 Security Audit runtime ACL

Before Flyway applies V176, run
[`database/prepare-v176-security-audit-runtime-acl.sql`](./database/prepare-v176-security-audit-runtime-acl.sql)
as the `iam.security_audit_event` table owner or an authorized database
administrator while the central schema is at V174 or V175. The restricted
`nexa_migrator` does not own this pre-existing table, so the script installs a
fixed owner-bound helper that grants only the one migration execution. V176
uses that helper to revoke runtime `SELECT`, `UPDATE`, and `DELETE`, preserves
runtime `INSERT`, then the helper revokes its own execution grant. V176 does
not add RLS or an alternate reader grant. Local Compose prepares the helper
automatically before the pending V175/V176 migrations. This change does not
execute the preparation or migration against a managed database.

### V177 central inbox RLS and Tenant V28

Before central Flyway applies V177, run
[`database/prepare-v177-inbox-event-rls.sql`](./database/prepare-v177-inbox-event-rls.sql)
as the `integration.inbox_event` table owner or an authorized database
administrator while central Flyway is at V174, V175, or V176. The script
installs a fixed helper owned by the inbox table owner because the restricted
`nexa_migrator` cannot inspect or alter the `integration` table directly. V177
fails closed if existing receipts disagree with their source event scope, then
forces direct Tenant/Workspace RLS and limits `nexa_runtime` to scoped
`SELECT`/`INSERT`. The helper revokes its own migration execution grant.

Local Compose and `CentralMigrationTestBootstrap` prepare the helper before
applying pending migrations. Physical Tenant databases apply V28 through the
Tenant capability selector; V28 forces the same scope policies and removes
the unused `nexa_runtime` inbox grants while preserving the traceability
worker's existing column grants. This repository change does not run either
migration against a managed database.

### V180 central change-feed runtime ACL and Tenant V30

Before central Flyway applies V180, run
[`database/prepare-v180-change-event-runtime-acl.sql`](./database/prepare-v180-change-event-runtime-acl.sql)
as the `integration.change_event` table owner or an authorized database
administrator while central Flyway is at V179. The script installs a
single-use helper owned by the change-event owner because the restricted
`nexa_migrator` cannot alter that bootstrap-owned table directly. V180 removes
all direct `nexa_runtime` privileges and restores only `SELECT` and `INSERT`;
the bounded `integration.purge_expired_change_events(integer)` function keeps
its existing `EXECUTE` grant. Direct `UPDATE`, `DELETE`, and `TRUNCATE` are
therefore unavailable through the runtime login even when a caller sets the
maintenance GUC. The helper revokes its own migration execution grant.

Local Compose and `CentralMigrationTestBootstrap` prepare the helper after
V179 and before V180. Physical Tenant databases apply V30 through the active
capability manifest; V30 similarly narrows Tenant runtime access to
`SELECT`/`INSERT` while preserving retention. This repository change does not
execute either migration against a managed database.

### V146 Tenant business database binding migration grant

V146 adds the central `tenant_management.tenant_business_database_binding`
table with a foreign key to the central Tenant registry. The restricted
`nexa_migrator` needs `REFERENCES` on `tenant_management.tenant` to create that
foreign key. Before Flyway applies V146, run
[`database/grant-v146-tenant-reference-to-migrator.sql`](./database/grant-v146-tenant-reference-to-migrator.sql)
once as a database provisioner that owns the Tenant table or has grant option.
The script grants only that table privilege to `nexa_migrator`; it does not
grant ownership, broad DDL rights, or any privilege to `nexa_runtime`. The
grant is a migration-role precondition and does not provision a Tenant business
database or perform a deployment or cutover. This repository change does not
execute it against any managed database.
