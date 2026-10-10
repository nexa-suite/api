# Buyer wallet storage foundation

**State:** implementation foundation; not a Buyer payment feature or database
cutover.

The accepted Owner decision defines stored funds as a PEN balance per Buyer and
supplier Tenant, separate from BC-07 commercial credit and receivables. BC-08
owns the immutable wallet ledger. BC-04 reserves funds against its authoritative
Sales Order, consumes a hold when that order is authoritatively confirmed, and
releases a hold when the order is cancelled. A refund is an explicit positive
ledger movement tied to previously consumed funds. Withdrawals and transfers
between supplier balances are outside the accepted scope.

Central migration V148 and tenant-local migration V5 add the wallet account,
append-only ledger, reservation state and append-only reservation events. V148
preserves the current shared-database implementation for compatibility tests;
it is not the HTTP wallet read source or a fallback for the Tenant database.
The target read path uses the opt-in Tenant database and fails closed when the
Tenant binding is disabled, missing or not ready. Both migrations
scope rows by Tenant and Workspace, enable forced row-level security and keep
`human_identity_id` as an opaque BC-01 reference without copying identity or
membership data. The active Buyer membership and BC-02 account relationship are
rechecked when the internal reservation command begins. The account is unique
by stable identity and supplier Tenant and is pinned to the workspace recorded
for that account. A command in another workspace fails closed; no automatic
second account or cross-workspace balance view is created. The current tenant
database anchor permits one Workspace per physical Tenant database; broader
cross-workspace wallet semantics remain unimplemented.

The BC-08 public module contract is for trusted BC-04 server calls. Reservation,
consumption, release and refund participate synchronously in the caller's local
transaction with `MANDATORY` propagation. Commands lock the wallet account
before checking available balance. Idempotency keys and unique order/source
constraints make repeated commands safe, while an append-only reservation event
records holds and terminal transitions. Consumption reduces posted and reserved
funds once; release reduces only the hold; refund cannot exceed the amount
consumed for that order. Each command receives a server-resolved
`CurrentAccessContext`; the adapter derives Tenant, Workspace and current
membership from it and rejects a mismatch with the established RLS scope before
querying wallet data. Reservation additionally rechecks that the current
membership is an active Buyer with an active BC-02 account relationship.

The accepted Buyer read contract is `GET /api/v1/buyer/wallet?page=0&size=25`,
authorized by the canonical `payment.read` permission and restricted to the
current verified Buyer. BC-01 verifies the access context and active Buyer
membership first. Within the opt-in Tenant database transaction, BC-02 checks
the active Buyer account relationship and BC-08 reads the wallet from that
same Tenant JDBC session. The projection separates posted, reserved and
available PEN balances and returns a bounded, paginated movement list with only
movement type, signed amount and occurrence time. It omits account, actor,
source, provider and other-party identifiers. A missing wallet is reported as
`NOT_INITIALIZED` with no balances or movements; a GET never creates an account
or credits funds. A disabled route or unready Tenant database is an explicit
unavailable result, not a zero balance or a shared-database lookup.

This foundation adds no payment option, Sales Order tender hook, provider
adapter, recharge intent, or provider callback credit command. It does not
write BC-07 credit or receivable data. A stored account is not initialized
with funds by the application; future credit work must connect ledger posting
to verified provider confirmation before a Buyer can top up or spend. The V5
migration extends the tenant-local schema independently of the V3 baseline and
does not change central database routing or activate cutover.

Technical tests use an explicit pre-existing-credit fixture to exercise
reservations. That fixture is not a provider integration, a user-facing top-up,
or evidence of real Buyer balances. The accepted read contract is grounded in
the Owner decision `01-shared/product/owner-decisions-2026-10.md` (BC-08 stored
funds and separation from BC-07) and `01-shared/security/authorization-matrix.md`
(Buyer payment reads are own-resource reads with Tenant/Workspace and
relationship checks). Its current implementation and OpenAPI snapshot remain
subject to the focused integration and architecture gates.
