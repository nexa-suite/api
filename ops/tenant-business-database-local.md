# Local Tenant business database provisioning

This opt-in local workflow creates one PostgreSQL database and persistent
Docker volume for a Tenant that already exists in the Modern central database.
The CLI provisions local infrastructure; the Modern API has a separate
local-profile Tenant router for explicitly enabled business paths. A routed
path requires a READY binding and verified schema manifest, and fails closed
when its Tenant route is unavailable. This is local development infrastructure;
it does not extract production data or perform a deployment or cloud cutover.

Start the existing Modern local environment first so its central Tenant,
Workspace, and V146 binding schema are available. Then provide the central
Tenant UUID, a Workspace UUID belonging to that Tenant, and an unused
loopback port. The tool requires a local Docker socket context and refuses
remote Docker contexts:

```bash
./ops/scripts/provision-local-tenant-database.sh \
  <tenant-uuid> <workspace-uuid> 5547
```

Tenant activation and local bootstrap enqueue idempotent provisioning and
Workspace-anchor tasks in the central database. `modern-up.sh` starts the local
worker that claims these tasks and invokes the provisioner; enqueueing alone
does not make a Tenant database ready. The command above remains available for
explicit local provisioning. `modern-down.sh` stops the worker before stopping
Compose services.

The default path reads the existing private `.env.local` and Modern Compose
service. Disposable integration harnesses can instead pass both
`--central-configfile <path>` and `--local-state-dir <path>`. The central config
must be a mode-`0600` file in a mode-`0700` directory containing exactly one
entry, `container-id=<64 lowercase hexadecimal characters>`. The referenced
container must be running, use the official PostgreSQL image, and provide
`psql`; the active Docker context must still be a local Unix/npipe socket. The
explicit state directory must be an absolute path beneath a mode-`0700` parent
and is created mode `0700` during provisioning. Read-only READY verification
requires the directory to exist and never creates it. This isolated path uses
the target container's local PostgreSQL socket and accepts no database host URL
or credentials in the config.
The normal application attachment target is `nexa-modern-api`. The E2E harness
may set `NEXA_LOCAL_API_CONTAINER_NAME` and `NEXA_LOCAL_API_COMPOSE_PROJECT` to
its unique disposable container name and project; the provisioner requires a
running container with the exact `modern-api` service and configured project
labels before attaching it. The unique project keeps `modern-down.sh` from
matching the disposable API anchor.

The integration-only worker path accepts `--once --task-id <uuid>` with the
same central config and an existing mode-`0700` state directory outside
`.local`. The initial provisioning run requires an empty state directory. A
retry with `--include-workspace-anchor` may contain only the selected Tenant's
owner-only state and private lock directory. That flag also processes the exact
anchor task for the selected task's Tenant and Workspace; the worker never
claims an unrelated queue item. The selected central PostgreSQL container must
carry `com.nexa.local-provisioning.disposable-test=true` and must not have
Compose project or service labels. A missing or unclaimable task exits without
claiming another queue item. Before that one-shot path exits successfully, it
rereads the exact provisioning task and Tenant/Workspace anchor and runs the
provisioner's `--verify-ready-only` check. That check confirms the existing
owner-private state matches the central Tenant, Workspace, database identity,
credential references, and port, and that the volume marker and Docker volume
belong to the Tenant's Compose project. It is read-only: missing or mismatched
state/volume fails without regenerating credentials, starting Compose, or
changing either READY task or binding. The continuous local worker remains the
normal path for the Modern Compose database.

Each Tenant uses a separate Compose project and persistent volume named from
the Tenant UUID. PostgreSQL publishes only on `127.0.0.1`. Its bridge network
disables IP masquerading so the host can reach the explicit loopback mapping
without granting the container outbound NAT. The port remains fixed for
retries. The tool refuses a volume without a central binding, a binding without
matching private local state, identity mismatches, and unsafe existing roles.
It never removes a volume or recreates credentials over existing state. Once
volume creation begins, it records that fact; if the volume later disappears,
provisioning stops for manual inspection instead of recreating an empty
database. A `READY` binding also fails if its local volume is missing.

The central database is consulted to verify the Tenant/Workspace relationship
and to advance the binding lifecycle with a version check. Each business
database has separate passwords for `nexa_tenant_bootstrap_admin`,
`nexa_migrator`, `nexa_runtime`, and `nexa_policy_snapshot_writer`. The
bootstrap password is used only by that isolated PostgreSQL container; Flyway
runs as `nexa_migrator`; API runtime credentials resolve only to
`nexa_runtime`. The snapshot writer has its own private properties file and
gets only the V4 identity/anchor reads plus snapshot SELECT/INSERT/UPDATE
grants. It does not reuse the runtime secret reference. Local provisioning
checks the writer role but does not copy BC01 settings or create snapshot rows.
The central `.env.local` credentials are not written to Tenant files or
supplied to Tenant database pools.

The central database also has a dedicated `nexa_tenant_database_provisioner`
login for the local provisioning worker and its child provisioner. It can read
the Tenant provisioning queues and update only their lifecycle columns; it
cannot create queue tasks or change their Tenant/Workspace ownership. The API's
`nexa_runtime` login retains queue reads and atomic inserts scoped to the active
Tenant/Workspace during organization activation, but cannot update queued work.
The two passwords are separate. The worker reads the provisioner password
from the private local environment and passes it to its child through a private
pipe. Both send the password over stdin to the transient central PostgreSQL
shell, which validates the generated Base64 format and writes a mode-0600
temporary passfile. `psql` receives only the passfile path through `PGPASSFILE`,
not the password in its environment or arguments. An exit trap removes the file
after success or failure; neither container environment nor Docker arguments
contain the password.
Central database access sets the Tenant and Workspace scope for each
provisioning command. This role is local opt-in
plumbing; Render still has no provisioning worker and no production cutover is
implemented.

Credentials and provisioning state live under the ignored
`.local/tenant-databases/<tenant-uuid>/` directory. The tool creates that
directory with mode `0700` and its files with mode `0600`. The local Java
runtime credentials provider accepts only a unique opaque `local-tenant-db:`
reference, owner-only files, the `nexa_runtime` role, a loopback JDBC URL for
host-side provisioning, and the UUID-bound `tenant-db-<uuid>` network alias for
API pools. The separate local policy-snapshot writer provider resolves by
Tenant UUID and physical database identity and accepts only its own owner-only
file, role, and loopback URL. The Modern API container does not mount the host
credential tree. A Compose helper reads that tree read-only, copies runtime,
policy-writer, and enabled worker property files into a named volume with
owner-only permissions for API UID `10001`, then the API mounts that volume
read-only. A network-isolated helper stays alive and syncs each Tenant
directory with staged creation and per-file atomic replacement; a newly
provisioned Tenant becomes visible without Docker socket access or API restart.
Reservation expiry uses a separate `reservation-expiry-worker.properties`
credential, `nexa_warehouse_reservation_expiry_worker` role, and V27 table and
column grants. Its local router is enabled only with Warehouse Operations and
requires the central READY binding, READY Workspace scope, and matching
physical Workspace anchor. It keeps a routed transaction open while each
reservation runs in its own transaction, so its configured maximum pool size
must be at least two; the local configuration rejects smaller values.
Bootstrap-admin and migrator credentials stay on the host. Running
`ops/compose/scripts/modern-up.sh` refreshes the projection and restarts the
helper/API. On the local Spring profile, explicitly enabled paths use the
Tenant router; the central database remains the global default, with no
central fallback for a selected Tenant route. Other profiles do not activate
this local router.

Provisioning records `PROVISIONING` before starting the Tenant database. It
loads the checked-in `tenant-business-migration-requirements.properties`
manifest, verifies the SQL asset for every migration required by the active
Tenant capabilities, applies the available Flyway migrations, and requires
every manifest version to be recorded successfully. Current manifest requires
these versions and capabilities:

- V1 `tenant-identity`; V2 `tenant-workspace`; V3 `tenant-business`.
- V4 `sales-purchase-request-expiry`; V5 `buyer-wallet`.
- V7 `wallet-purchase-request-tender`; V8 `fulfillment-dispatch-actor`.
- V9 `buyer-wallet-provider-recharge`; V10 `buyer-wallet-recharge-event-outcomes`.
- V11 `tenant-multiple-workspace-anchors`; V12 `tenant-business-documents-worker`.
- V13 and V21 `tenant-payments-callback-worker`; V14, V17, V18, V19, V20 and V23
  `tenant-business-traceability-worker`.
- V15 and V23 `tenant-business-notification-projection`; V16 `buyer-wallet-refunds`;
  V22, V24, V25 and V33 `tenant-business`; V26 `tenant-parent-scoped-rls`; V27
  `tenant-reservation-expiry-worker`; V28 `tenant-inbox-event-rls`; V29
  `tenant-buyer-catalog-favorites`; V30 `tenant-change-event-append-only`; V31
  `tenant-document-generation-workspace-rls`; V34 `tenant-buyer-sales-messaging`;
  V35 and V41 `tenant-buyer-delivery-acceptance`; V36
  `tenant-buyer-saved-payment-methods`; V37
  `tenant-client-account-buyer-memberships`; V38 and V39
  `tenant-buyer-personal-address-book`; V40 `tenant-catalog-sku-variant-family`;
  V42 `tenant-payments-callback-worker`; V43 `tenant-buyer-personal-address-book`.

V6 is intentionally absent. V32 is not required by any capability in the
current manifest. The manifest records the exact SQL asset for every required
version. Central V162 `bound_tenant_push_delivery_retry_state` is a
separate migration in the Modern central schema; this manifest applies Tenant
V23 to the Tenant database. Missing assets, unapplied versions, or a manifest digest
mismatch keep the Tenant binding out of `READY`. The CLI repeats these
migration checks during `verify`, before the script can mark the binding
`READY`. It then seeds and verifies the Tenant database identity and Workspace
scope anchor, verifies the runtime role can read those metadata rows but cannot
write them, and checks that the separate policy-snapshot writer role has only
its V4 grants. The provisioner leaves the V4 snapshot empty. An empty local snapshot means no
centrally validated policy projection has been refreshed; it does not mean the
central BC01 policy is absent or undefined. The central source remains
`tenant_management.operational_settings`: a refresh must read its current
version and value, or confirm that the setting is absent (which uses the V4
three-day default), before a business write can use the policy. The business
transaction must also provide the matching snapshot revision. The provisioner
does not perform this refresh. Current Tenant-bound purchase-request
transitions, material-change commands, draft submission, and Purchase Request
conversion invoke the resolver, which refreshes the snapshot and installs its
matching revision in the business transaction. V4's trigger rejects
policy-sensitive writes without a validated, matching snapshot. The
provisioner rechecks the central relationship and
marks the binding `READY` with a compare-and-set version transition. Any
failure after provisioning begins attempts the same version-checked transition
to `FAILED`. Retry a `FAILED` binding only with `--retry-failed` and the same
Tenant, Workspace, port, and private local credentials. A `READY` binding is
left unchanged while its verified schema-manifest digest matches the active
manifest. When `modern-up.sh` detects a changed digest, it requeues only the
matching `READY` provisioning task for that `READY` Tenant binding. The worker
migrates the existing local database, preserving its identity, port, volume,
and Workspace anchors; routing stays fail-closed until the binding is verified
`READY` with the new digest. This reconciliation is part of the local setup
flow; no production provisioning worker or cutover is claimed.

`READY` means the local physical database, schema versions in the active
capability manifest, identity, Workspace anchor, runtime role, and dedicated
writer role passed provisioning checks. Any newly enabled Tenant capability
must add its required versions and exact SQL assets to that manifest before
the local provisioner can mark a database `READY`. This is schema-provisioning
evidence, not proof that every API path is wired or accepted. A routed
purchase-request command also depends on the current BC01 policy source and a
matching per-transaction revision; the request-time resolver performs that
refresh. See the
[routing foundation](../docs/architecture/tenant-database-routing-foundation.md)
for the broader boundary and cutover limits.

The local provider and `READY` state are provisioning evidence for this local
database only. They do not establish production readiness, business-data
extraction, complete system acceptance, or API cutover. Local secret rotation
is not automated by this tool; do not edit the binding or credential files to
simulate a rotation.
