# Changelog

All notable changes to this project are documented in this file.
The project uses Semantic Versioning.

## [0.15.0] - 2026-08-26

Fulfillment & Financial Completion implementation wave for the Nexa API.
Ownership is aligned with the eleven canonical Blueprint bounded contexts;
legacy storage names remain documented compatibility projections.

### Added

- Added Inventory Availability physical allocation with FEFO lot selection, safety-stock protection, release/reconciliation and dispatch consumption.
- Added Fulfillment & Delivery lifecycle commands for picking, packing, staging, readiness, handover, delivery attempts, partial/final outcomes, POD sealing and temperature evidence.
- Added append-only picking discrepancy resolution, continuation delivery facts, financial adjustments, ledger entries, receivable applications and refund/credit obligations.
- Added BC-11 Business Traceability writes on the existing `audit.event` and `integration.outbox_event` backbone, including `BusinessFactTraced.v1`.
- Added additive PostgreSQL migration `V91__fulfillment_financial_completion.sql` with tenant/workspace RLS, optimistic-concurrency columns and command idempotency.
- Added canonical per-context documentation under `docs/architecture/bounded-contexts/`.

### Changed

- Refactored Java module roots to the exact canonical BC names: Tenant Access Governance, Customer Buyer Relationships, Catalog Commercial Policy, Sales Commitment, Inventory Availability, Fulfillment Delivery, Credit Receivables, Payments, Business Documents, Notifications and Business Traceability.
- Added ETag/If-Match enforcement to the v0.15 fulfillment and delivery write surface while preserving v0.14 contracts.
- Bumped Maven and runtime contract version to `0.15.0`.

### Validation

- Local Maven compile, focused domain tests, architecture tests and whitespace validation pass.
- PostgreSQL/Testcontainers migration, RLS, OpenAPI runtime and full release gates require the configured Docker/runtime environment and must be run before publication.

## [0.14.0] - 2026-08-25

Commercial and Inventory Core release for the Nexa API. This release completes
the additive commercial commitment, direct order, authoritative availability,
credit reservation and terminal-lifecycle foundation without introducing a new
bounded context or downstream fulfillment semantics.

### Added

- Added direct internal Sales Order creation with `DIRECT_ORDER` origin and no synthetic Purchase Request.
- Added atomic commercial commitment and inventory backing orchestration with deterministic multi-warehouse coverage and safety-stock protection.
- Added PREPAID and IMMEDIATE payment policy handling, payment-gated confirmation, credit reservations and durable idempotency payload conflict detection.
- Added PostgreSQL migration `V89__commercial_inventory_core.sql` with tenant/workspace RLS for inventory backing and compatibility-preserving legacy Sales Order origin metadata.

### Changed

- Added immutable commercial pricing and origin snapshots to commitment and Sales Order projections.
- Added absolute Purchase Request expiry materialization and release behavior using an injected UTC clock.
- Added v0.14 OpenAPI, migration, concurrency, idempotency and transaction-boundary coverage.

### Validation

- Fresh and upgrade PostgreSQL/Flyway validation passes through migrations `V89` and `V90`.
- Full release validation and signed release evidence are recorded at release time.

## [0.13.0] - 2026-08-25

Backend Foundation Closure release for the Nexa API. This release closes technical
foundation contracts and does not introduce new V1 product semantics or bounded contexts.

### Added

- Added canonical outbox identity, occurrence keys, durable worker retry and non-destructive published-payload retention.
- Added lease expiry recovery and claim-token fencing for security notifications, document generation and payment reconciliation workers.
- Added durable payment reconciliation retry idempotency with request-hash conflict detection and provider-status-aware refund outcomes.
- Added PostgreSQL migrations `V87` and `V88` for claim fencing, reconciliation state support, RLS and durable retry idempotency.
- Added RLS scope classification, worker contract documentation and OpenAPI compatibility checks.

### Changed

- Strengthened external-provider transaction boundaries, retry limits, keyset pagination and technical error HTTP mapping.
- Updated CI security, CodeQL and supply-chain workflows to run for release branches.
- Bumped project version and canonical OpenAPI snapshot to `0.13.0`.

### Fixed

- Prevented stale worker claims from being finalized by a prior worker and removed reconciliation worker starvation across workspaces.
- Preserved durable retry results and rejected payload reuse under the same idempotency key.

### Validation

- Clean Maven test suite: 408 tests passed, 0 failures, 0 errors; 106 profile-dependent skips.
- Fresh and upgrade PostgreSQL/Flyway validation passed through migration `V88`.
- Docker build-time verification, OpenAPI compatibility, architecture, RLS and security/load CI gates passed.

## [0.12.0] - 2026-08-23

Tenant onboarding draft persistence and visual convergence backend alignment.

### Added

- Added organization registration draft persistence endpoints and database migration (`V86__create_organization_onboarding_drafts.sql`).
- Added draft validation and error handling for tenant onboarding workflow.

### Changed

- Bumped project version to `0.12.0`.

### Validation

- Spring Boot tests and verification suites pass.

## [0.11.0] - 2026-08-23

PRE-V1 Architecture & Governance Foundation release for the API.

### Added

- Customer & Buyer Relationships boundary extraction, establishing dedicated customer account query services and client relationship interfaces.
- Purchase Request expiry persistence foundation with UTC absolute timestamps (`expires_at`) and automatic validation.
- Tenant & Workspace governance invariants enforcing exactly-one Company Owner protection and single workspace operation.
- Token and session revocation hardening on tenant suspension/deactivation with stateful epoch validation.
- Row Level Security (RLS) policies and tenant isolation boundary hardening across transactional schemas.

### Changed

- Realigned cross-module communication to use explicit public application contracts between business capabilities.
- Strengthened Spring Modulith and ArchUnit architecture fitness rules preventing direct boundary leaks.
- Improved payment credit account bootstrapping on initial reservation request.

### Known limitations

- Commercial Commitment currently uses warehouse reservation backing; canonical independent Inventory Reservation and Credit & Receivables separation remain scheduled for next construction wave.

### Validation

- Clean build, 398 unit/integration/architecture tests passed (105 profile-dependent skips).
- Flyway migration and upgrade verification passed.
- Spring Modulith and ArchUnit architectural compliance verified.

## [0.10.0] - 2026-08-22

Functional convergence continuation baseline for the API.

### Added

- Consolidated Purchase Request and Sales Order continuation foundations across the current API baseline.
- Integrated the current security, tenant, catalog, warehouse, logistics, payments and document runtime work.

### Changed

- Aligned ArchUnit, Testcontainers, PostgreSQL, Springdoc and PDFBox dependency baselines.
- Preserved the boundary that Payments is broader than Stripe and that technical foundations do not imply complete business capability acceptance.

### Validation

- API CI, integration verification, CodeQL, container and security-load gates passed for the release candidate.
