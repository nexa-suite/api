# Cloud Tenant database routing foundation

Status: **incomplete implementation foundation, disabled by default**. This
does not make Buyer or Operations cloud workflows available, provision Neon
databases, migrate existing Tenants or authorize cutover. `render.yaml` is
unchanged. No managed deployment or database verification is claimed.

The `tenant-cloud` Spring profile adds an explicit
`NEXA_TENANT_BUSINESS_CLOUD_ROUTER_ENABLED` opt-in, defaulting to `false`.
Combining this profile with `local`, `test` or `local-fixtures` fails startup.
Development bootstrap, demo fixtures and every currently implemented
`nexa.tenant-business.<capability>.enabled` flag are rejected in this profile.
Those capabilities still require complete cloud composition, including their
workers and callback adapters, before activation. Keep the router disabled in
deployed services until the remaining gates below have evidence.

When explicitly enabled in an isolated verification environment, the foundation
requires `nexa.database.require-least-privilege-runtime=true` and two absolute
secret directory paths:

- `NEXA_TENANT_BUSINESS_CLOUD_RUNTIME_CREDENTIALS_DIRECTORY`
- `NEXA_TENANT_BUSINESS_CLOUD_POLICY_WRITER_CREDENTIALS_DIRECTORY`

The existing secure-runtime validator remains active. The runtime uses the
existing central membership revalidation, trusted READY binding registry,
bounded Tenant pools, required migration-manifest digest, physical Tenant and
database identity checks, Workspace anchor and RLS scope. It adds no central
or cross-Tenant fallback. Pool creation remains lazy; constructing the router
does not attest a database or change a binding to READY.

## Mounted secret contract

[Render secret files](https://render.com/docs/configure-environment-variables#secret-files)
are available at `/etc/secrets/<filename>`. Configure explicit directories;
there is no default credential location or environment-password fallback.
Directories must be real directories without group/other write permissions.
Secret files must be regular files without symlinks and have POSIX mode `0400`
or `0600`. Actual Render mount ownership, permissions and readability must be
verified before use; this change does not assume or alter those properties.
The two providers may use distinct directories; their filenames and accepted
property sets remain separate even when Render mounts them in one directory.

For a runtime reference `cloud-tenant-db:<opaque>`, mount
`tenant-runtime-<opaque>.properties`. The token must be 32–128 lowercase ASCII
letters, digits, underscores or hyphens, beginning with a letter or digit.
Generate an independent random token per Tenant/credential version; never use
a password, URL or Tenant selector as the reference. The file must contain
exactly these nonempty properties, with no duplicate keys:

| Property | Required value |
| --- | --- |
| `credential-secret-reference` | Exact central opaque reference |
| `jdbc-url` | Restricted direct Neon JDBC URL described below |
| `username` | `nexa_runtime` |
| `password` | Separate runtime login secret |

For the policy writer, mount
`tenant-policy-writer-<tenant-uuid>-<database-identity-uuid>.properties`.
The file must contain exactly `tenant-id`, `database-identity`, `jdbc-url`,
`username` and `password`. Both identities must equal the authoritative binding;
the username must be `nexa_policy_snapshot_writer`. Runtime, migrator and owner
logins are refused. The existing writer also verifies physical database and
Workspace identities before scoped writes. This provider does not provision
or broaden that role's grants.

Both JDBC URLs must target a direct `ep-….<region>.aws.neon.tech` or
`ep-….<region>.azure.neon.tech` hostname, explicit port `5432` and database
`nexa_tenant_business`. The known `ep-….c-<number>.<region>.aws.neon.tech`
and Azure equivalent are also accepted. Pooler hosts, credentials in URLs, fragments and
arbitrary JDBC options are rejected. Only these three query parameters are
accepted, once each, in any order:

- `sslmode=verify-full`
- `sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`
- `channelBinding=require`

[pgJDBC SSL documentation](https://jdbc.postgresql.org/documentation/ssl/)
distinguishes certificate/hostname validation from the weaker `require` mode.
The approved Java SSL factory uses the JVM trust store; that trust store and
channel-binding compatibility need live verification with the deployed driver
and Neon endpoint. Direct endpoints preserve the existing session-scope
checkout/cleanup contract; compatibility with transaction-mode external
poolers has not been established.

## Remaining cloud gates

- Complete each requested capability's cloud boundary composition, callbacks,
  workers and scoped credentials, together with the central-fallback guards.
- Provide a managed provisioning/verification path that proves exact Tenant
  and Workspace identities, immutable migrations, manifest attestation,
  restricted role flags/effective grants and binding state transitions.
- Rehearse existing-data migration, reconciliation, concurrent isolation,
  failure recovery, rotation and rollback/roll-forward before any cutover.
- Verify Render secret permissions, secure runtime configuration, Neon TLS and
  driver compatibility, then run authenticated Buyer and Operations flows.

Unit/context tests and compilation are Technical Verification evidence only;
they do not establish cloud delivery, Product/UX Acceptance, System Acceptance
or Production Readiness.
