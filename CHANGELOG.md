# Changelog

All notable changes to this project are documented in this file.
The project uses Semantic Versioning.

## [Unreleased]

### Documentation

- Aligned the README, suite map and runtime diagram with the tagged `v0.3.0` API foundation.
- Made non-implemented persistence, identity, tenant, AI, IoT, cloud and mobile scope explicit.
- Added a local repository map and a release-notes index.

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

[Unreleased]: https://github.com/nexa-suite/api/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/nexa-suite/api/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/nexa-suite/api/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/nexa-suite/api/releases/tag/v0.1.0
