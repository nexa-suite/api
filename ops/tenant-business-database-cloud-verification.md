# Verify an existing Neon Tenant database

This verify-only deployment adapter reuses the local physical identity,
Workspace anchor and least-privilege SQL checks. It never migrates, provisions,
assigns passwords, grants privileges or changes a central binding. Successful
verification is Technical Verification of the inspected database snapshot;
it is not Product Acceptance or authorization for additional capabilities.

## Private input

Use an absolute private properties file (`0400` or `0600`, regular file, no
symlinks) in a real directory without group/other write permissions. Supply
exactly these nonempty keys; never commit the file or pass secret values as
command arguments:

- `tenant-id`, `workspace-id`, `database-identity`: independently confirmed
  canonical UUIDs, not values inferred solely from the restored database.
- `credential-secret-reference`: intended central `cloud-tenant-db:<opaque>`
  reference; use the existing cloud runtime token contract.
- `jdbc-url`: direct Neon endpoint, explicit port `5432`, database
  `nexa_tenant_business`, and exactly `sslmode=verify-full`,
  `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`, `channelBinding=require`.
- `runtime-password`, `policy-writer-password`, `migrator-password`: existing
  dedicated Tenant login credentials. Role names are fixed to `nexa_runtime`,
  `nexa_policy_snapshot_writer`, `nexa_migrator`.
- `central-jdbc-url`, `central-runtime-password`, `central-migrator-password`:
  actual central deployment settings, used only to reject the same compute
  host and reused credentials. No central connection is opened. All five
  passwords must differ. Separate Neon branches/computes remain required.

The file uses Java properties syntax, not shell quoting. Runtime and writer
passwords must already be initialized through separately reviewed operations.
The CLI never accepts an owner login or a migration mode. Keep this temporary input only for the verification window: it contains central and Tenant credentials. Remove the generated input after verification; retain only redacted results and the manifest digest. Do not delete the authoritative credential stores.

## Run the exact candidate

From the candidate checkout, build the classpath and invoke the plain Java CLI
with the repository's Java 25 runtime:

```sh
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/cloud-verification-classpath
java -cp "target/classes:$(cat target/cloud-verification-classpath)" \
  com.nexa.api.bootstrap.runtime.database.tenant.cloud.CloudTenantBusinessDatabaseVerificationCli \
  --config=/absolute/private/cloud-verification.properties
```

Success prints only the computed manifest digest and a completion statement.
Failure exits nonzero with a fixed redacted diagnostic. No secret, JDBC URL or
underlying exception is printed by the CLI. Flyway logging is disabled for this
operation. Its only Flyway operations are `validate` and `info`; required SQL
assets and exact applied versions must match the candidate manifest.

Runtime, writer and migrator authenticate separately. Runtime and writer
connections verify physical identity and Workspace anchor as well as the
existing effective privilege restrictions. The migrator connection also checks
physical identity and Workspace before Flyway validation. Connections used for
physical checks are marked read-only.

## Explicit Neon ownership policy

Local verification is unchanged: the bootstrap owner and zero role memberships
remain mandatory. Cloud verification instead requires database owner
`neondb_owner`. The only membership rows involving runtime, migrator or writer
that it permits have that application role as parent, `neondb_owner` as member,
`cloud_admin` as grantor, ADMIN true, INHERIT false and SET false. Any outbound
application-role membership, other member/grantor, or INHERIT/SET ability fails.
No other local privilege check is relaxed.

## Binding attestation and bounded activation

The supplied credential reference is validated syntactically, not compared to
central state. Before activation, independently verify the exact central
Tenant/Workspace, intended reference, database identity, current lifecycle and
version. A copied READY row or copied manifest is not fresh cloud attestation.

Use separately reviewed, transactionally guarded central operations: suspend
and clear stale attestation with an expected-version comparison, replace a
local reference only through specifically authorized administration, and set
READY with the newly verified digest only when tenant/database/reference/state
and expected version still match. Require exactly one affected row and advance
version. Existing provisioner grants deliberately do not allow reference
replacement; do not broaden them. The CLI does not perform these operations or
claim their completion.

After those gates, follow [bounded cloud read configuration](tenant-business-database-cloud.md).
Enable catalog list/detail/filter, Buyer own-account reads and existing authorized Platform customer list/detail reads. Verify Sales account ownership and deny Buyer management access; do not infer customer-specific pricing or offers. The independent notification flag may enable the existing HTTP inbox, unread count, read-state and personal preferences. Workspace notification-settings writes remain unavailable; push delivery, projections and callbacks remain outside this delivery. Preserve
central authentication, fail-closed routing and all disabled unsupported
capabilities. Verify authenticated positive and wrong-scope negative requests
against the exact deployed API and mobile builds. Disable flags and restore
the reviewed binding state if verification fails; do not redirect to central
business tables or replay restored pending work.
