# Cloud Tenant database routing foundation

Status: **bounded read composition implemented, disabled by default**. This
does not provision Neon databases, migrate existing Tenants or authorize cutover.
`render.yaml` is unchanged. No managed deployment, database verification or
authenticated device acceptance is claimed by this document.

The `tenant-cloud` Spring profile adds an explicit
`NEXA_TENANT_BUSINESS_CLOUD_ROUTER_ENABLED` opt-in, defaulting to `false`.
For the existing Render service, retain its S3 storage profile by setting
`SPRING_PROFILES_ACTIVE=s3,tenant-cloud` when this delivery is enabled.
Combining this profile with `local`, `test` or `local-fixtures` fails startup.
Development bootstrap and demo fixtures are rejected in this profile. The
explicit read composition requires all three switches:

- `NEXA_TENANT_BUSINESS_CLOUD_ROUTER_ENABLED=true`
- `NEXA_TENANT_BUSINESS_CLOUD_READ_ENABLED=true`
- `NEXA_TENANT_BUSINESS_CATALOG_DETAIL_READ_ENABLED=true`

Partial read activation fails startup. The composition uses the existing
Tenant-routed catalog list, detail and filter queries, existing SKU-code/GTIN resolution (`GET /api/v1/skus/resolve?identifier=...`) for Operations scanning, and the existing
authorized Buyer self-account read (`GET /api/v1/client-accounts/me`) plus existing authorized Platform customer list/detail reads (`GET /api/v1/client-accounts` and `GET /api/v1/client-accounts/{id}`). The existing server policy still denies Buyer management reads and limits Sales to its assigned accounts. This enables customer selection without new business rules. It does not enable Client Account administration, membership reads, addresses or mutations: the cloud wrapper rejects every other `ClientAccountUseCase` method. Customer-specific offers and pricing are not established by generic catalog reads. Native authentication, context selection and
personal-profile access continue through their existing central authority.
An independent `NEXA_TENANT_BUSINESS_NOTIFICATIONS_ENABLED=true` opt-in enables the existing Tenant-routed HTTP inbox, unread count, read-state and personal-preference operations. Workspace notification-settings writes remain unavailable in cloud because their central security audit is outside the Tenant transaction. Push delivery, background projections and callbacks are not enabled or verified by this composition.

All other Tenant business capability flags remain rejected. Those capabilities
still require their complete cloud composition, workers and callback adapters.
Keep this profile disabled in deployed services until the remaining gates below
have evidence.

When explicitly enabled in an isolated verification environment, the foundation
requires `nexa.database.require-least-privilege-runtime=true` (environment:
`NEXA_DATABASE_REQUIRE_LEAST_PRIVILEGE_RUNTIME=true`). The default
`NEXA_TENANT_BUSINESS_CLOUD_CREDENTIALS_SOURCE=files` requires two absolute
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

## Explicit environment secret source

When mounted secret ownership or permissions do not satisfy the file contract,
the runtime may explicitly select
`NEXA_TENANT_BUSINESS_CLOUD_CREDENTIALS_SOURCE=environment`. This is an independent
source, not a fallback. An unknown source or incomplete material fails startup;
files, local credentials and owner logins are never tried after a failure.

This bounded provider accepts exactly one configured Tenant database binding:

| Environment variable | Value |
| --- | --- |
| `NEXA_TENANT_BUSINESS_CLOUD_CREDENTIAL_REFERENCE` | Authoritative `cloud-tenant-db:<opaque>` reference with the same token restrictions as files |
| `NEXA_TENANT_BUSINESS_CLOUD_TENANT_ID` | Exact canonical Tenant UUID |
| `NEXA_TENANT_BUSINESS_CLOUD_DATABASE_IDENTITY` | Exact physical database identity UUID |
| `NEXA_TENANT_BUSINESS_CLOUD_JDBC_URL` | Restricted direct Neon JDBC URL above |
| `NEXA_TENANT_BUSINESS_CLOUD_RUNTIME_USERNAME` | `nexa_runtime` |
| `NEXA_TENANT_BUSINESS_CLOUD_RUNTIME_PASSWORD` | Dedicated Tenant runtime secret |
| `NEXA_TENANT_BUSINESS_CLOUD_POLICY_WRITER_USERNAME` | `nexa_policy_snapshot_writer` |
| `NEXA_TENANT_BUSINESS_CLOUD_POLICY_WRITER_PASSWORD` | Different dedicated policy-writer secret |

An unknown runtime reference or a different writer Tenant/database pair is
refused. Runtime and writer passwords must differ from each other and from
the configured central runtime/migrator passwords. Errors and credential
`toString` output redact connection material. Configure secrets only in the
deployment's protected environment; do not put them in Git, logs or release
notes. Environment source does not change the physical-identity, manifest,
Workspace or RLS checks.

PostgreSQL roles belong to a cluster. Since both central and Tenant databases
use the restricted role name `nexa_runtime`, a separate Neon branch/compute is
required to keep their passwords and grants independent under this contract.
Two databases in one branch do not provide that credential separation.

## Remaining cloud gates

- Verify this bounded read composition against the exact deployed central and
  Tenant snapshots and APKs. Other capability composition, callbacks and workers
  remain outside this read delivery.
- Provide a managed provisioning/verification path that proves exact Tenant
  and Workspace identities, immutable migrations, manifest attestation,
  restricted role flags/effective grants and binding state transitions.
- Rehearse existing-data migration, reconciliation, concurrent isolation,
  failure recovery, rotation and rollback/roll-forward before any cutover.
- Verify Render secret permissions, secure runtime configuration, Neon TLS and
  driver compatibility, then run authenticated Buyer and Operations flows.

For an isolated read rehearsal, disable scheduled work using
`NEXA_SCHEDULING_ENABLED=false` and `SPRING_MODULITH_MOMENTS_ENABLED=false`.
Do not replay copied pending outbox, provisioning, password-reset or push work.
The read paths do not refresh or rewrite purchase-request expiry snapshots;
purchase-request commands remain disabled. A copied timestamp must not be
presented as a fresh policy attestation.

Unit/context tests and compilation are Technical Verification evidence only;
they do not establish cloud delivery, Product/UX Acceptance, System Acceptance
or Production Readiness.


## Mobile stock and preparation reads

With the verified cloud router already configured, the independent opt-in
`NEXA_TENANT_BUSINESS_CLOUD_OPERATIONS_READ_ENABLED=true` routes existing
inventory list/lot/detail, per-Warehouse availability, fulfillment work-list/detail
and physical-allocation GET requests to the authorized
Tenant database. They retain permission and Warehouse-grant checks and run in
PostgreSQL `READ ONLY`, `REPEATABLE READ` transactions. Missing bindings,
physical identities, schema manifests or scope fail closed. No fallback to
central business tables is introduced.

Keep the general Warehouse and Fulfillment flags disabled. The read flag does
not enable mutations, workers or complete inventory/picking workflows. Verify
both authorized reads and rejected commands before rehearsing Mobile. A
successful empty list indicates no visible business rows; it is not evidence
that data were seeded or a workflow completed.
