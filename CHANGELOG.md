# Changelog

All notable changes to this project are documented in this file.
The project uses Semantic Versioning.

## [Unreleased]

### Documentation

- Kept future sales, warehouse, logistics, invoicing, AI, IoT, cloud and mobile scope explicit while documenting the secured identity and workspace slice.
- Added a local repository map and a release-notes index.

## [0.4.0] - 2026-07-28

### Added

- Catalog query Application ports, immutable models, seed adapter and explicit projections.
- `GET /api/v1/catalog-items` with bounded search, filters, pagination and sorting.
- `GET /api/v1/catalog-items/{catalogItemId}` for active catalog detail.
- Explicit REST DTOs, Problem Details, local OpenAPI and deny-by-default security handlers.
- Modern Docker image and canonical dual-runtime Compose profiles.
- ADRs for the query contract, security boundary and seed-only runtime.

### Security

- Anonymous catalog access returns `401`; authenticated access without an approved rule returns `403`.
- Health/info remain public; local OpenAPI is profile-gated.

## [0.5.0] - 2026-07-28

### Added

- Flyway-managed IAM and tenant-management schemas for PostgreSQL 18.4.
- RS256 access tokens, HttpOnly refresh-cookie rotation and session-family reuse revocation.
- Tenant/workspace/membership verification, surface policy and canonical permissions.
- Secured Catalog read endpoints and local-only OpenAPI for the six approved routes.

### Security

- No browser token storage, public sign-up, user-management or Catalog write endpoint.
- Catalog reference data remains shared local seed data; tenant-specific pricing and stock are not claimed.

## [0.3.0] - 2026-07-28

### Added

- Catalog Management domain foundation.
- Catalog seed anticorruption mapping.
- Domain invariants and purity tests.
- Client and automation compatibility constraints.
- Suite-wide repository documentation.

### Changed

- Catalog stock ownership is assigned to Warehouse rather than Catalog Management.
- Brand and Category remain value concepts until independent lifecycle evidence exists.

### Fixed

- Removed verified duplicate local artifacts.

### Security

- Added coordinated vulnerability reporting guidance.

## [0.2.0] - 2026-07-28

### Added

- Correlation ID filter with safe propagation, MDC cleanup and response header.
- Safe Problem Details factory and global exception handling.
- Byte-exact canonical catalog seed, immutable loader, checksum and integrity validation.
- Spring Boot generated build metadata for Actuator info.

### Changed

- API description now identifies the business and integration API, without baseline-only metadata.

## [0.1.0] - 2026-07-28

### Added

- Independent Spring Boot 4.1 modular monolith package structure and Actuator health/info application.

[Unreleased]: https://github.com/nexa-suite/api/compare/v0.4.0...HEAD
[0.5.0]: https://github.com/nexa-suite/api/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/nexa-suite/api/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/nexa-suite/api/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/nexa-suite/api/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/nexa-suite/api/releases/tag/v0.1.0
