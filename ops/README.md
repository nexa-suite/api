# API operations

The current local API runtime is the modern Compose stack in
[`compose/modern.compose.yml`](./compose/modern.compose.yml). Prepare the local
environment with `scripts/setup-local-environment.sh`, then use
`compose/scripts/modern-up.sh`, `modern-down.sh` and `status.sh`.

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
