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

Stabilization and release closure baseline for v0.11.0 milestone.

### Added

- Synchronized release governance and release closure metadata.

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
