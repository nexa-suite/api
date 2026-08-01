# Changelog

All notable changes to this project are documented in this file.
The project uses Semantic Versioning.

## [0.8.1] - 2026-08-01

Operations release stabilization for the consolidated commercial, Warehouse and Logistics baseline.

### Fixed

- Sales Order creator identity, aggregate-owned lifecycle and concurrent conversion replay.
- Warehouse FEFO/expiration forward migration and route-start application ownership.
- Dispatch number initialization and OpenAPI release metadata.

### Changed

- Warehouse and Logistics application boundaries and dedicated operational evidence were strengthened.

### Added

- Internal TASK-011 document-subject vocabulary and lookup contract only.

Business Documents, storage, files, invoices and payments are not implemented.

## [0.8.0] - 2026-07-31

This release consolidates the previously unpublished TASK-NEXA-008, TASK-NEXA-008.6, TASK-NEXA-009, TASK-NEXA-010 and TASK-NEXA-010.5 work.

### Added

- Sales Order conversion and lifecycle, secure Change Feed and Workspace Preview.
- Warehouse topology, Inventory Lots, Stock Movements, FEFO reservations and Dispatch Readiness.
- Dispatch Orders, temperature readings, incidents, Proof of Delivery metadata and Buyer delivery tracking APIs.

### Changed

- Sales Order lifecycle orchestration is application-owned.
- FEFO reservation and route-start consumption are server-authoritative and atomic.
- Permission policy, idempotency, optimistic concurrency and Buyer visibility boundaries are enforced at the API.

### Security

- Authentication throttling, session revalidation, tenant/client isolation and role-specific Warehouse/Logistics authorization are covered by the release gate.

## [0.7.0] - 2026-07-30

### Added

- Atomic, idempotent Purchase Request to Sales Order conversion from `APPROVED` requests only.
- Tenant/workspace/year Sales Order sequence, immutable order snapshots, lifecycle events and fulfillment-candidate query.
- Secure PostgreSQL change feed with bounded SSE replay and session/context revalidation.

### Changed

- Added strict commercial filters, server-side snapshots, event identifiers and Purchase Request event timeline queries.
- Added API CI, CodeQL and Dependabot configuration.

### Security

- Buyer reads remain client-account scoped; Sales Order mutations require `sales:write` and internal commercial roles.
- Change-feed events expose no sensitive payload and are filtered by tenant, workspace and buyer scope.

## [0.6.0] - 2026-07-30

This release consolidates previously unreleased Identity, tenant, security and commercial vertical work. Intermediate planned versions were never published.

### Added

- IAM and session lifecycle, PostgreSQL identity and tenant persistence, Organization Administration, Client Accounts and Purchase Requests.
- Audit events, idempotency, optimistic concurrency control and authentication throttling.
- OpenAPI contracts, Docker Modern stack and Sales Order domain readiness without persistence or HTTP.

### Changed

- Catalog now requires authenticated permission; bearer authentication revalidates persistent sessions; tenant/workspace access uses verified membership.
- Sales Presentation contracts, Application models, domain packages and persistence adapters are separated by responsibility.

### Fixed

- Access JWT use after logout, refresh-family reuse behavior, dynamic route aliases, canonical Flyway drift handling, changelog links and duplicate Sales Order concepts.

### Security

- Session revocation, durable throttling, CSRF/Origin controls, security headers, server-authoritative price snapshots, tenant/client isolation and secret-safe local verification.

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

[Unreleased]: https://github.com/nexa-suite/api/compare/v0.8.0...HEAD
[0.8.0]: https://github.com/nexa-suite/api/compare/v0.6.0...v0.8.0
[0.7.0]: https://github.com/nexa-suite/api/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/nexa-suite/api/compare/v0.4.0...v0.6.0
[0.4.0]: https://github.com/nexa-suite/api/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/nexa-suite/api/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/nexa-suite/api/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/nexa-suite/api/releases/tag/v0.1.0
