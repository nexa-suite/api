<div align="center">

<img src="./docs/assets/nexa.svg" alt="Nexa" width="220" />

# Nexa API

**Business and integration backbone for the Nexa product ecosystem.**

[![Java 25](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://jdk.java.net/25/) [![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot) [![Release](https://img.shields.io/github/v/release/nexa-suite/api?style=flat-square&label=release)](https://github.com/nexa-suite/api/releases)

[Changelog](./CHANGELOG.md) · [Release notes](./docs/releases/) · [Contributing](./.github/CONTRIBUTING.md) · [Security](./.github/SECURITY.md)

</div>

---

## Overview

Spring Boot modular monolith. API owns identity, tenant/workspace access, Catalog, commercial, Warehouse and Logistics contracts. Current development contracts for documents, invoicing, payments and public contact intake remain unreleased until provider, fiscal and end-to-end gates pass.

## Business capabilities

- Identity sessions, refresh rotation and membership verification.
- Tenant-scoped Catalog and pricing read contracts.
- Client Accounts, Purchase Requests and Sales Orders.
- Inventory, FEFO reservations, dispatch and delivery tracking.
- Problem Details, idempotency, optimistic concurrency and Flyway-managed PostgreSQL schemas.
- Local OpenAPI for contract inspection.

Development evidence is not published product evidence. API remains business authority; clients do not duplicate domain rules.

## Nexa Product Ecosystem

<table>
<tr><td><a href="https://github.com/nexa-suite/website"><strong>Nexa Website</strong></a><br />Public product discovery.<br /><img src="https://img.shields.io/github/v/release/nexa-suite/website?style=flat-square&label=release" alt="Website release" /></td><td><a href="https://github.com/nexa-suite/platform"><strong>Nexa Platform</strong></a><br />Internal operational workspace.<br /><img src="https://img.shields.io/github/v/release/nexa-suite/platform?style=flat-square&label=release" alt="Platform release" /></td></tr>
<tr><td><a href="https://github.com/nexa-suite/portal"><strong>Nexa Buyer Portal</strong></a><br />Buyer-facing business experience.<br /><img src="https://img.shields.io/github/v/release/nexa-suite/portal?style=flat-square&label=release" alt="Portal release" /></td><td><strong>Nexa API</strong><br />This repository. Business and integration authority.<br /><img src="https://img.shields.io/github/v/release/nexa-suite/api?style=flat-square&label=release" alt="API release" /></td></tr>
<tr><td colspan="2"><a href="https://github.com/nexa-suite/mobile"><strong>Nexa Mobile</strong></a><br />Architecture runway for future native clients.<br /><img src="https://img.shields.io/github/v/release/nexa-suite/mobile?style=flat-square&label=release" alt="Mobile release" /></td></tr>
</table>

## Architecture

Presentation, Application, Domain and Infrastructure remain separate. Domain code stays framework-free. Catalog seed loading belongs to Infrastructure; seed data is not a persistence entity. C4 and domain documentation live in Blueprint, when accessible.

## Technology

Java 25, Spring Boot 4.1, Spring MVC, Spring Security resource server, JPA infrastructure, Flyway, PostgreSQL, Bean Validation, Actuator and Maven Wrapper.

## Getting started

    ./mvnw clean test
    ./mvnw spring-boot:run

Local health and info endpoints use port 8080. Swagger/OpenAPI is enabled only with the local profile. Local checks do not imply a released public business API.

## Validation

    ./mvnw clean test
    ./mvnw verify

## Repository structure

    src/main/java/com/nexa/api/       Application and domain packages
    src/main/resources/seed/catalog/  Canonical catalog seed and checksum
    src/test/java/com/nexa/api/       HTTP, domain and seed-integrity tests
    docs/                              Domain, architecture, OpenAPI and releases

## Documentation

- [OpenAPI instructions](./docs/openapi/README.md)
- [Authentication contract](./docs/security/authentication.md)
- [Release notes](./docs/releases/)
- [Changelog](./CHANGELOG.md)

## Security

Do not report vulnerabilities through public issues. Follow the [Security Policy](./.github/SECURITY.md) for responsible disclosure.

## Legal

Copyright © 2026 Nexa. All rights reserved. No open-source license is selected by this README.

<div align="center"><br />Nexa · Business authority with tenant boundaries</div>
