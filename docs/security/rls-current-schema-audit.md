# Current-schema isolation audit — central V188 source / local V188

Status: **V174/V175 central migrations, V174 historical backfill, runtime RLS,
and dedicated Workspace scope-reader isolation passed focused PostgreSQL 18.4
Testcontainers runs.** `RlsRuntimeDatabaseIsolationIT` passed 17 tests and
`WorkspaceScopeReaderIsolationIT` passed 6. The local Docker central database
was observed at V188 on 2026-10-22. No deployment or data cutover is claimed.

The [central V173 table inventory](./rls-table-inventory-v173.tsv) remains a
historical point-in-time record. The
[central V174 source inventory](./rls-table-inventory-v174.tsv) classifies the
same 217 database tables, including Flyway metadata, and records the V174
point-in-time table/RLS classification inventory. V175 further narrows global Workspace and
Tenant discovery to its dedicated reader role; this ACL/policy change has no
separate generated table inventory. V170 applies
forced parent-scoped RLS to ten business/configuration child tables; V173 adds
forced RLS to `tenant_management.workspace_membership` while preserving
identity discovery. V174 adds a non-null `tenant_id`, backfills it from
Workspace while the V173 forced policy is active, and enforces a validated
composite foreign key to `workspace(tenant_id,id)`. Its write policy requires
both current Tenant and Workspace; the identity SELECT policy remains unchanged.
The V174 inventory classifies membership as direct Tenant/Workspace scope.

| Classification | Tables | RLS enabled and forced |
| --- | ---: | ---: |
| `FORCED_RLS_DIRECT_SCOPE` | 181 | 181 |
| `FORCED_RLS_PARENT_SCOPE` | 10 | 10 |
| `INHERITED_SCOPE_JUSTIFIED` | 0 | 0 |
| `GLOBAL_REFERENCE` | 5 | 0 |
| `GLOBAL_IDENTITY_SECURITY` | 7 | 0 |
| `TECHNICAL_GLOBAL` | 7 | 0 |
| `CROSS_SCOPE_WORKER` | 7 | 0 |
| `APPROVED_EXCEPTION` | 0 | 0 |
| **Total** | **217** | **191** |

The physical Tenant V26 migration covers these six child/projection tables:
`sales.purchase_request_line`, `sales.sales_order_line`,
`warehouse.inventory_reservation_line`,
`warehouse.inventory_reservation_allocation`,
`warehouse.reservation_shortage`, and
`tenant_management.notification_preference`. Child scope comes from the
scoped parent; notification preference scope comes from the verified local
Tenant/Workspace anchor. Central migration V170 applies parent-scoped RLS to
the first five business children plus five central configuration/governance
children. The central inventory above and the physical Tenant migration are
separate schema lines.

V172 closes a confirmed view-level RLS bypass. The legacy
`logistics.buyer_delivery_tracking` view was owned by superuser `nexa` and had
default owner-based view security, so `nexa_runtime` could read through forced
RLS on `dispatch_order` and `proof_of_delivery`. The migration sets
`security_invoker = true`; the dedicated V172 preparation transfers only that
view to `nexa_migrator` with temporary schema `CREATE`, then removes that grant.
The migration role gets no base-table `SELECT`. PostgreSQL 18.4 integration
coverage checks actual login identity and verifies missing scope, matching
scope, and foreign-Tenant scope against a synthetic dispatch row. The central
database has no other views or materialized views; the Tenant database has
none. Its two superuser-owned security-definer retention functions delete only
expired rows; the provisioner scope helper is owned by `nexa_migrator`, and
runtime cannot execute it.

The V154/V155 GUC-only policies are historical, not the current policy set.
V169 drops the provisioning and Workspace-anchor queue policies that included
`app.cross_scope_workspace_scan`, replacing them with exact Tenant/Workspace
scope for runtime reads and inserts and a provisioner-only update policy. V175
retains cross-scope scans of global `tenant_management.workspace` and
`tenant_management.tenant`, but those SELECT policies require both
`current_user = nexa_workspace_scope_reader` and the transaction-local scan
GUC. The runtime uses a dedicated scope-reader login. Before scanning, it
executes `SET LOCAL ROLE nexa_workspace_scope_reader`. Setting the GUC on
`nexa_runtime` therefore does not enable either scan path.

V178 replaces the GUC-only `app.current_internal_operator_id` policies on
registration, provisioning queues, Workspace-anchor tasks, database bindings,
and internal support requests/audit with policies that also require
`current_user = nexa_internal_operator`. V179 guards the isolated operator
role/login membership and privileges. V181 removes runtime `INSERT` on support
requests and operator approval/revocation grants, and removes
support-audit reads from `nexa_runtime`. It retains scoped Company Owner
updates and consent/revocation audit inserts. The API uses a separate operator
database login and role. Its HTTP filter validates the configured operator
ID/token digest before controllers pass the authenticated operator ID to
persistence. Together, these migrations close the documented GUC-only paths
for `nexa_runtime`; V182–V188 do not reintroduce them.

Residual trust boundary: `app.current_internal_operator_id` is still
caller-settable, and the operator RLS policies check only that it is a
UUID-shaped value once `current_user` is `nexa_internal_operator`. A
compromised session using that isolated database role can choose another
operator ID. The database does not independently verify the HTTP token or bind
the GUC to an operator identity; that binding remains an application control.

The V146 per-table evidence in
[`rls-direct-scope-evidence-v146.tsv`](./rls-direct-scope-evidence-v146.tsv)
remains a point-in-time record. Retained V100, V102, V107, V108, V141, V142,
V146, V161, V170, and V173 table inventories are historical snapshots, not
current-schema verification. The V174/V175 source checks are verified against disposable
PostgreSQL. The V174 inventory is a source artifact. Separately, the local
Docker central database was observed at V188 on 2026-10-22; this observation is
not deployment or data cutover evidence.

The reviewed pre-context policies remain capability-bound:

- Tenant and Workspace discovery uses a configured bootstrap slug, requested
  Workspace slug, verified membership, authenticated identity, or an explicit
  transaction-local worker scan setting.
- Invitation acceptance reads one pending row by the presented opaque token
  hash; writes occur after Tenant/Workspace resolution.
- Public registration access requires the row ID plus opaque status-token
  hash, or the authorized operator path naming one registration ID.
- Global role templates are readable; runtime role-definition writes are
  limited to Tenant/Workspace-scoped custom roles.

V146's `tenant_management.tenant_business_database_binding` remains a central
control-plane table with direct Tenant-only RLS and runtime `SELECT` only.
The central API still contains business schemas as AS-IS migration evidence;
the accepted target is one physical business database per Tenant. Selected
local-profile adapters use the opt-in router, and complete request/worker
routing and data cutover remain unverified. See the
[Tenant physical-database routing foundation](../architecture/tenant-database-routing-foundation.md).

The migration inventory classifies `public.flyway_schema_history` as
`TECHNICAL_GLOBAL`; it is Flyway metadata, not business data. No application
business table is expected in `public`.
