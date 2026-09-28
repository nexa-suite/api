<div align="center">

<br />

<img src="./docs/assets/nexa.svg" alt="Nexa" width="240" />

# Nexa API

**Authoritative business and integration backbone for Nexa Suite.**

![Java 25](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot 4.1.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?style=flat-square&logo=springboot&logoColor=white) ![PostgreSQL 18.4](https://img.shields.io/badge/PostgreSQL-18.4-4169E1?style=flat-square&logo=postgresql&logoColor=white) ![Maven](https://img.shields.io/badge/Maven-build-C71A36?style=flat-square&logo=apachemaven&logoColor=white) ![Latest Git tag](https://img.shields.io/github/v/tag/nexa-suite/api?sort=semver&style=flat-square&label=latest%20Git%20tag)

[OpenAPI](./docs/openapi/README.md) · [Architecture](./docs/architecture/) · [Releases](./docs/releases/) · [Contributing](./.github/CONTRIBUTING.md) · [Security](./.github/SECURITY.md)

</div>

---

## Overview

Nexa API is the authoritative Spring Boot modular monolith for identity, tenant
and workspace scope, commercial workflows, inventory, fulfillment, delivery,
finance, documents, notifications and traceability. The current `v0.17.1` Git tag
identifies the latest tagged repository baseline and provides selected
contracts for Mobile integration; it does not claim a
completed Mobile Product or Product Acceptance.

The current implementation candidate is `0.18.0` and is not a published
release. The repository retains the v0.17.0 release notes; it has no historical
v0.17.1 release-note file.

The API remains the server authority for authorization, tenant isolation,
business decisions and durable state. Java packages and Spring Modulith
modules are implementation structure, not automatic Bounded Contexts.

## Nexa Product Ecosystem

<table>
<tr>
<td width="50%" valign="top">

### [Nexa Mobile Report](https://github.com/nexa-suite/mobile-report)

Academic report and delivery evidence for Nexa Mobile.

![Markdown](https://img.shields.io/badge/Markdown-academic%20evidence-000000?style=flat-square&logo=markdown&logoColor=white)

</td>
<td width="50%" valign="top">

### [Nexa Mobile](https://github.com/nexa-suite/mobile)

Partial Operations Android/Kotlin/Jetpack Compose implementation evidence is
integrated in the current Mobile baseline, not a completed Mobile V1. Buyer
Mobile remains an accepted Flutter/Dart target.

![Operations Android](https://img.shields.io/badge/Operations%20Mobile-partial%20evidence-3DDC84?style=flat-square&logo=android&logoColor=white) ![Buyer target](https://img.shields.io/badge/Buyer%20Mobile-TARGET%20Flutter%2FDart-64748B?style=flat-square)

</td>
</tr>
<tr>
<td width="50%" valign="top">

### [Nexa API](https://github.com/nexa-suite/api)

This repository: business and integration authority.

![Java](https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?style=flat-square&logo=springboot&logoColor=white)

</td>
<td width="50%" valign="top">

### [Nexa Website](https://github.com/nexa-suite/website)

Public product experience and acquisition entry point.

![HTML5](https://img.shields.io/badge/HTML5-static-E34F26?style=flat-square&logo=html5&logoColor=white) ![CSS3](https://img.shields.io/badge/CSS3-responsive-1572B6?style=flat-square&logo=css3&logoColor=white) ![JavaScript](https://img.shields.io/badge/JavaScript-vanilla-F7DF1E?style=flat-square&logo=javascript&logoColor=black)

</td>
</tr>
<tr>
<td width="50%" valign="top">

### [Nexa Buyer Portal](https://github.com/nexa-suite/portal)

Buyer-facing Web experience for B2B purchasing and delivery visibility.

![Angular](https://img.shields.io/badge/Angular-22-DD0031?style=flat-square&logo=angular&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?style=flat-square&logo=typescript&logoColor=white)

</td>
<td width="50%" valign="top">

### [Nexa Platform](https://github.com/nexa-suite/platform)

Internal operational Web workspace for tenant teams.

![Angular](https://img.shields.io/badge/Angular-22-DD0031?style=flat-square&logo=angular&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?style=flat-square&logo=typescript&logoColor=white)

</td>
</tr>
</table>

## Implemented API Areas

- Identity sessions, refresh rotation and membership verification.
- Browser-cookie and native-header refresh transports over the same session
  lifecycle.
- Tenant-scoped catalog, pricing, customer, Purchase Request and Sales Order
  contracts.
- Inventory Availability, FEFO, safety stock, lot controls and physical
  allocation.
- Fulfillment and Delivery lifecycle, picking discrepancies, dispatch,
  handover, delivery attempts, POD and temperature evidence.
- Credit, receivables, financial adjustments and payment foundations with
  provider boundaries kept explicit.
- Business Traceability through the append-only audit and outbox backbone.
- Problem Details, idempotency, optimistic concurrency and Flyway-managed
  PostgreSQL schemas.

Development evidence is not Product Acceptance evidence. The API does not add
Mobile, Scanner, QR or Device Bounded Contexts.

## Architecture and Authority Boundary

Presentation, Application, Domain and Infrastructure remain separate. Domain
code stays independent of Spring, persistence, JSON and SQL concerns where the
architecture requires it. The canonical module map and ownership notes live in
[`docs/architecture/bounded-context-module-map.md`](./docs/architecture/bounded-context-module-map.md).

`edge`, `bootstrap` and `shared` are technical modules, not Bounded Contexts.
`edge` composes inbound HTTP, security, Problem Details and change-feed
adapters. `bootstrap.runtime` contains runtime-wide wiring and workers;
`bootstrap.local` contains local development bootstrap. `shared` retains
framework-neutral technical contracts and primitives. Client applications
consume approved contracts; they do not become business or persistence
authorities.

## Technology Stack

| Concern | Current evidence |
| --- | --- |
| Language | Java 25 |
| Runtime | Spring Boot 4.1.1 |
| HTTP/API | Spring MVC, REST and Springdoc OpenAPI 3.1.0 |
| Security | Spring Security resource server |
| Persistence | JPA infrastructure and PostgreSQL 18.4 local/Testcontainers baseline |
| Migrations | Flyway with PostgreSQL support |
| Build | Maven Wrapper |
| Operations | Spring Boot Actuator and OpenTelemetry support |

## Getting Started

```bash
./mvnw clean test
./mvnw spring-boot:run
```

Local health and information endpoints use port `8080`. Swagger/OpenAPI is
enabled with the local profile. Local execution does not imply a released
public business API.

## Validation

```bash
./mvnw clean test
./mvnw clean verify -Dnexa.integration.enabled=true
```

The integration gate requires Docker/Testcontainers and the configured external
adapter test doubles. A unit-test pass does not replace that gate.

## Repository Structure

```text
src/main/java/com/nexa/api/       Eleven business context roots
                                  plus edge/, bootstrap/ and shared/
src/main/java/com/nexa/api/edge/  Inbound HTTP, security, errors and streaming
src/main/java/com/nexa/api/bootstrap/{runtime,local}/ Runtime and local startup
src/main/java/com/nexa/api/shared/ Framework-neutral technical primitives
src/main/resources/               Migrations, seeds, schemas and configuration
src/test/java/com/nexa/api/       Tests organized by source ownership
docs/                              Implementation maps, contracts and evidence
ops/                               Compose runtime and operational tooling
scripts/                           Local setup, access checks and evidence
```

## Documentation

- [Documentation index](./docs/README.md)
- [OpenAPI instructions](./docs/openapi/README.md)
- [Authentication contract](./docs/security/authentication.md)
- [Runtime database role](./docs/security/runtime-database-role.md)
- [Canonical bounded contexts](./docs/architecture/bounded-context-module-map.md)
- [Release notes](./docs/releases/)
- [Changelog](./CHANGELOG.md)
- [Runtime operations](./ops/README.md)
- [Developer scripts](./scripts/README.md)

## Nexa Engineering & Documentation

<table>
<tr>
<td width="50%" valign="top">

### [Nexa Blueprint](https://github.com/nexa-suite/blueprint)

Canonical Product, Domain, Architecture, data, security and accepted
engineering decision source.

![C4](https://img.shields.io/badge/C4-canonical%20model-64748B?style=flat-square) ![Markdown](https://img.shields.io/badge/Markdown-documentation-000000?style=flat-square&logo=markdown&logoColor=white)

</td>
<td width="50%" valign="top">

### [Nexa Web Report](https://github.com/nexa-suite/web-report)

Academic report and evidence repository for the Nexa Web course.

![Docs as Code](https://img.shields.io/badge/Docs%20as%20Code-academic%20evidence-64748B?style=flat-square)

</td>
</tr>
<tr>
<td width="50%" valign="top">

### [Nexa Complementary](https://github.com/nexa-suite/complementary)

Supporting references, reproducible engineering resources and shared tooling;
not Product, Domain or Architecture authority.

![Support tooling](https://img.shields.io/badge/Support%20tooling-reference-64748B?style=flat-square)

</td>
<td width="50%" valign="top">

### [Nexa Design Lab](https://github.com/nexa-suite/design-lab)

UX/UI, interaction, design-system, prototype and current design-evidence
workspace.

![Angular](https://img.shields.io/badge/Angular-22-DD0031?style=flat-square&logo=angular&logoColor=white)

</td>
</tr>
</table>

## Security

Follow the repository [Security Policy](./.github/SECURITY.md) for reporting
vulnerabilities. Tenant isolation, authorization, secret handling and data
integrity remain server-side responsibilities.

## Legal

Copyright © 2026 Nexa. All rights reserved. No open-source license is claimed
by this README.

<div align="center"><br />Nexa · Server authority, explicit evidence boundaries</div>
