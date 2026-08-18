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

Spring Boot modular monolith. API owns identity, tenant/workspace access, Catalog, commercial, Warehouse and Logistics contracts. Current development contracts for documents, invoicing, payments and public contact intake remain unreleased until their provider, fiscal and end-to-end gates pass.

## Nexa Product Ecosystem


<table>
<tr>
<td width="50%" valign="top">

### [Nexa Website](https://github.com/nexa-suite/website)

Public product experience and entry point for Nexa.

[Open Repository](https://github.com/nexa-suite/website)

![HTML5](https://img.shields.io/badge/HTML5-static-E34F26?style=flat-square&logo=html5&logoColor=white) ![CSS3](https://img.shields.io/badge/CSS3-responsive-1572B6?style=flat-square&logo=css3&logoColor=white) ![JavaScript](https://img.shields.io/badge/JavaScript-vanilla-F7DF1E?style=flat-square&logo=javascript&logoColor=black) ![Release](https://img.shields.io/github/v/release/nexa-suite/website?display_name=tag&sort=semver&style=flat-square&label=release)

</td>
<td width="50%" valign="top">

### [Nexa Platform](https://github.com/nexa-suite/platform)

Internal operational workspace for tenant teams, sales, warehouse and logistics.

[Open Repository](https://github.com/nexa-suite/platform)

![Angular](https://img.shields.io/badge/Angular-22-DD0031?style=flat-square&logo=angular&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?style=flat-square&logo=typescript&logoColor=white) ![Material](https://img.shields.io/badge/Angular%20Material-22-757575?style=flat-square&logo=materialdesign&logoColor=white) ![Release](https://img.shields.io/github/v/release/nexa-suite/platform?display_name=tag&sort=semver&style=flat-square&label=release)

</td>
</tr>
<tr>
<td width="50%" valign="top">

### [Nexa Buyer Portal](https://github.com/nexa-suite/portal)

Buyer-facing experience for catalog discovery, purchasing and delivery visibility.

[Open Repository](https://github.com/nexa-suite/portal)

![Angular](https://img.shields.io/badge/Angular-22-DD0031?style=flat-square&logo=angular&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?style=flat-square&logo=typescript&logoColor=white) ![Material](https://img.shields.io/badge/Angular%20Material-22-757575?style=flat-square&logo=materialdesign&logoColor=white) ![Release](https://img.shields.io/github/v/release/nexa-suite/portal?display_name=tag&sort=semver&style=flat-square&label=release)

</td>
<td width="50%" valign="top">

### [Nexa API](https://github.com/nexa-suite/api)

Business and integration backbone for identity, tenant scope and operational workflows.

[Open Repository](https://github.com/nexa-suite/api)

![Java](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?style=flat-square&logo=postgresql&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-migrations-CC0200?style=flat-square&logo=flyway&logoColor=white) ![Release](https://img.shields.io/github/v/release/nexa-suite/api?display_name=tag&sort=semver&style=flat-square&label=release)

</td>
</tr>
<tr>
<td colspan="2" valign="top">

### [Nexa Mobile](https://github.com/nexa-suite/mobile)

Documentation and native runway for future buyer and cold-chain field experiences. No application framework selected.

[Open Repository](https://github.com/nexa-suite/mobile)

![Status](https://img.shields.io/badge/status-planned-64748B?style=flat-square) ![Documentation](https://img.shields.io/badge/scope-documentation-64748B?style=flat-square) ![Native Runway](https://img.shields.io/badge/native-runway-64748B?style=flat-square)

</td>
</tr>
</table>


## Business Capabilities

- Identity sessions, refresh rotation and membership verification.
- Tenant-scoped Catalog and pricing read contracts.
- Client Accounts, Purchase Requests and Sales Orders.
- Inventory, FEFO reservations, dispatch and delivery tracking.
- Problem Details, idempotency, optimistic concurrency and Flyway-managed PostgreSQL schemas.
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
    ./mvnw verify

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


## Project Lineage

Historical repositories belong to old UPC organization. They are provenance, not current Nexa product surfaces.

<table>
<tr>
<td width="50%" valign="top">

### [Legacy Backend](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-platform)

Repository lineage: nexa-platform. Predecessor backend and REST API service layer.

[Open Repository](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-platform)

![.NET](https://img.shields.io/badge/.NET-10-512BD4?style=flat-square&logo=dotnet&logoColor=white) ![ASP.NET Core](https://img.shields.io/badge/ASP.NET%20Core-REST-0EA5E9?style=flat-square) ![EF Core](https://img.shields.io/badge/EF%20Core-PostgreSQL-512BD4?style=flat-square)

</td>
<td width="50%" valign="top">

### [Legacy WebApp](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-webapp)

Repository lineage: nexa-webapp. One unified historical application covering workflows now separated between Platform and Buyer Portal.

[Open Repository](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-webapp)

![Vue](https://img.shields.io/badge/Vue-3-35495E?style=flat-square&logo=vuedotjs&logoColor=white) ![Vite](https://img.shields.io/badge/Vite-5-646CFF?style=flat-square&logo=vite&logoColor=white) ![PrimeVue](https://img.shields.io/badge/PrimeVue-4-0EA5E9?style=flat-square) ![Pinia](https://img.shields.io/badge/Pinia-2-FFD859?style=flat-square)

</td>
</tr>
<tr>
<td width="50%" valign="top">

### [Legacy Website](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-website)

Repository lineage: nexa-website. Previous public Website lineage.

[Open Repository](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-website)

![HTML5](https://img.shields.io/badge/HTML5-static-E34F26?style=flat-square&logo=html5&logoColor=white) ![CSS3](https://img.shields.io/badge/CSS3-responsive-1572B6?style=flat-square&logo=css3&logoColor=white) ![JavaScript](https://img.shields.io/badge/JavaScript-vanilla-F7DF1E?style=flat-square&logo=javascript&logoColor=black)

</td>
<td width="50%" valign="top">

### [Academic / Historical Reference](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-ecosystem-report)

Repository lineage: nexa-ecosystem-report. Documentation, requirements, project evidence and historical architecture material.

[Open Repository](https://github.com/upc-pre-202610-1asi0730-12242-king/nexa-ecosystem-report)

![Scope](https://img.shields.io/badge/scope-documentation-64748B?style=flat-square) ![Role](https://img.shields.io/badge/role-historical%20reference-64748B?style=flat-square)

</td>
</tr>
</table>



## Security

Do not report vulnerabilities through public issues. Follow the repository [Security Policy](./.github/SECURITY.md).

## Legal

Copyright © 2026 Nexa. All rights reserved. No open-source license is selected by this README.

<div align="center"><br />Nexa · Current product, explicit evidence boundaries</div>
