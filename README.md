<div align="center">

<br />

<img src="./docs/assets/nexa.svg" alt="Nexa" width="240" />

# Nexa API

**Business and integration backbone for the Nexa product ecosystem.**

![Java 25](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?style=flat-square&logo=postgresql&logoColor=white) ![Maven](https://img.shields.io/badge/Maven-build-C71A36?style=flat-square&logo=apachemaven&logoColor=white) ![Release](https://img.shields.io/github/v/release/nexa-suite/api?display_name=tag&sort=semver&style=flat-square&label=release)

[Changelog](./CHANGELOG.md) · [Release notes](./docs/releases/) · [Contributing](./.github/CONTRIBUTING.md) · [Security](./.github/SECURITY.md)

</div>

---

## Overview

Spring Boot modular monolith. API owns identity, tenant/workspace access, Catalog, commercial, Warehouse and Logistics contracts. Business Documents, storage/evidence and Payments/Stripe technical foundations are present in this runtime and database baseline; that presence does not claim accepted Product capability, provider/fiscal completion or complete end-to-end business semantics.

## Related repositories

The organization profile owns the full public ecosystem map. This repository links to adjacent Nexa surfaces without copying their release state.

- [Nexa Platform](https://github.com/nexa-suite/platform) — internal operational workspace.
- [Nexa Buyer Portal](https://github.com/nexa-suite/portal) — buyer-facing experience.
- [Nexa Website](https://github.com/nexa-suite/website) — public product experience.
- [Nexa Mobile](https://github.com/nexa-suite/mobile) — documentation and native runway.

## Implemented API Areas

- Identity sessions, refresh rotation and membership verification.
- Tenant-scoped Catalog and pricing read contracts.
- Client Accounts, Purchase Requests and Sales Orders.
- Inventory, FEFO reservations, dispatch and delivery tracking.
- Problem Details, idempotency, optimistic concurrency and Flyway-managed PostgreSQL schemas.
- Business Document rendering/storage/evidence and Payments/Stripe technical foundations, with Product acceptance boundaries kept explicit.
- Local OpenAPI for contract inspection.

Development evidence is not published product evidence. API remains business authority; clients do not duplicate domain rules.

## Architecture

Presentation, Application, Domain and Infrastructure remain separate. Domain code stays framework-free. Catalog seed loading belongs to Infrastructure; seed data is not a persistence entity.

## Technology Stack

| Concern | Technology |
| --- | --- |
| Language | Java 25 |
| Runtime | Spring Boot 4.1.x |
| API | Spring MVC, REST and OpenAPI |
| Security | Spring Security resource server |
| Persistence | JPA infrastructure and PostgreSQL |
| Migrations | Flyway |
| Build | Maven Wrapper |
| Operations | Actuator |

## Getting Started

    ./mvnw clean test
    ./mvnw spring-boot:run

Local health and info endpoints use port 8080. Swagger/OpenAPI is enabled only with the local profile. Local checks do not imply a released public business API.

## Validation

    ./mvnw clean test
    ./mvnw clean verify -Dnexa.integration.enabled=true

The integration gate requires Docker/Testcontainers and the repository's configured external-adapter test doubles. A local unit-test pass does not replace this gate.

## Repository Structure

    src/main/java/com/nexa/api/       Application and domain packages
    src/main/resources/seed/catalog/  Canonical catalog seed and checksum
    src/test/java/com/nexa/api/       HTTP, domain and seed-integrity tests
    docs/                              Domain, architecture, OpenAPI and releases

## Documentation

- [OpenAPI instructions](./docs/openapi/README.md)
- [Authentication contract](./docs/security/authentication.md)
- [Release notes](./docs/releases/)
- [Changelog](./CHANGELOG.md)


## Historical provenance

Earlier UPC repositories remain evidence only. They do not define current Nexa identity, implementation authority or TARGET architecture.

- [nexa-platform](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-platform) — predecessor backend and REST API layer.
- [nexa-webapp](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-webapp) — historical unified Vue application.
- [nexa-website](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-website) — previous public Website lineage.
- [nexa-ecosystem-report](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-ecosystem-report) — historical requirements and architecture evidence.


## Security

Do not report vulnerabilities through public issues. Follow the repository [Security Policy](./.github/SECURITY.md).

## Legal

Copyright © 2026 Nexa. All rights reserved. No open-source license is selected by this README.

<div align="center"><br />Nexa · Current product, explicit evidence boundaries</div>
