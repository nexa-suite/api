<div align="center">

<img src="./docs/assets/nexa.svg" alt="Nexa Logo" width="250"/>

# Nexa API

Shared Spring Boot modular monolith for Nexa Suite.

[![Java 26](https://img.shields.io/badge/Java-26-ED8B00?logo=openjdk&logoColor=white)](https://dev.java/)
[![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![Modular Monolith](https://img.shields.io/badge/architecture-Modular%20Monolith-2563EB)](#architecture)
[![DDD](https://img.shields.io/badge/architecture-DDD-2563EB)](#architecture)
[![Status](https://img.shields.io/badge/status-baseline%20v0.1.0-16A34A)](#current-status)

[Platform](https://github.com/nexa-suite/platform) · [Portal](https://github.com/nexa-suite/portal) · [API](https://github.com/nexa-suite/api)

</div>

---

## Overview

Nexa API is the shared backend authority being prepared for Nexa Suite business rules, contracts, security, tenant isolation, persistence and integrations.

## Role in the Nexa Ecosystem

API will serve both independent Angular applications through explicit HTTP contracts. This baseline exposes only Spring Boot Actuator information.

```mermaid
flowchart LR
    Platform["Nexa Platform"] --> Api["Nexa API"]
    Portal["Nexa Buyer Portal"] --> Api

    subgraph Monolith["Spring Modular Monolith"]
        Presentation --> Application
        Application --> Domain
        Infrastructure --> Application
        Infrastructure --> Domain
    end

    Api -. contains .-> Monolith
```

## Repository Map

| Repository | Responsibility | Technology |
|---|---|---|
| [Platform](https://github.com/nexa-suite/platform) | Internal operations for Sales, Warehouse, Logistics and Administration | Angular |
| [Portal](https://github.com/nexa-suite/portal) | Buyer-facing B2B experience | Angular |
| **API** — This repository | Business rules, contracts, security and persistence authority | Spring Boot |

## Scope

- Independent Spring Boot application.
- Modular monolith bounded-context structure.
- Explicit domain, application, infrastructure and presentation layers.
- Actuator health and info baseline.
- No business endpoints.

## Architecture

Each bounded context contains Presentation, Application, Domain and Infrastructure packages.

Presentation adapts HTTP. Application coordinates use cases and ports. Domain owns business rules. Infrastructure implements technical adapters.

## Bounded Contexts

- Shared.
- IAM.
- Tenant Management.
- Catalog Management.
- Sales.
- Warehouse.
- Logistics.
- Invoicing.

## Tech Stack

- Java 26.
- Spring Boot 4.1.
- Maven and Maven Wrapper.
- Spring Web MVC, Bean Validation, Actuator and Spring Boot Test.
- Jar packaging.

## Getting Started

```bash
./mvnw clean test
./mvnw spring-boot:run
```

Open [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health).

## Available Commands

```bash
./mvnw clean package
./mvnw spring-boot:run
```

## Project Structure

```text
docs/assets/                 # Local Nexa documentation asset
src/main/java/com/nexa/api/ # Application and bounded contexts
src/main/resources/          # application.yml and local profile
src/test/java/com/nexa/api/  # Baseline context test
pom.xml                      # Spring Boot and dependency contract
```

## Current Status

This repository currently contains the approved architecture baseline.

Business capabilities, API integrations, persistence and security will be implemented incrementally through vertical slices.

## Out of Scope

- Authentication.
- Authorization.
- Persistence.
- PostgreSQL.
- Migrations.
- Business endpoints.
- Frontend integration.
- Deployment.

## Roadmap

1. Architecture baseline.
2. First approved vertical slice.
3. Security and tenant isolation.
4. Persistence and contracts.
5. Progressive legacy parity.
