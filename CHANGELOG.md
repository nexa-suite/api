# Changelog

All notable changes to this project are documented in this file.
The project uses Semantic Versioning.

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
