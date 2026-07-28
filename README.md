<div align="center">

<img src="./docs/assets/nexa.svg" alt="Nexa Logo" width="250"/>

# Nexa API

Shared Spring Boot modular monolith for Nexa Suite.

[![Java 26](https://img.shields.io/badge/Java-26-ED8B00?logo=openjdk&logoColor=white)](https://dev.java/) [![Spring Boot 4.1](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot) [![Maven](https://img.shields.io/badge/build-Maven-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)

[Changelog](./CHANGELOG.md) · [v0.2.0 release notes](./docs/releases/v0.2.0.md) · [GitHub Releases](https://github.com/nexa-suite/api/releases)

[Platform](https://github.com/nexa-suite/platform) · [Portal](https://github.com/nexa-suite/portal) · [API](https://github.com/nexa-suite/api)

</div>

---

## Overview

Nexa API is the Spring Boot authority for future Nexa business and integration contracts.

## Role in the Nexa Ecosystem

API serves the independent Angular applications through approved HTTP contracts. v0.2.0 adds transversal HTTP foundations and the canonical catalog seed authority without exposing catalog REST endpoints.

## Repository Map

| Repository | Responsibility | Technology |
|---|---|---|
| [Platform](https://github.com/nexa-suite/platform) | Internal operations | Angular |
| [Portal](https://github.com/nexa-suite/portal) | Buyer-facing B2B experience | Angular |
| **API** — This repository | Business and integration API | Spring Boot |

## Scope

- Independent Spring Boot application and modular DDD package structure.
- Correlation ID filter and safe Problem Details error foundation.
- Immutable canonical catalog seed loader, checksum and integrity validator.
- Actuator health/info with generated build metadata.
- No catalog REST, persistence, authentication, authorization or tenant runtime.

## Architecture

Each bounded context contains Presentation, Application, Domain and Infrastructure packages. Shared HTTP/error code is transport infrastructure; catalog seed loading is catalog infrastructure and is not a persistence entity.

## Bounded Contexts

Shared, IAM, Tenant Management, Catalog Management, Sales, Warehouse, Logistics and Invoicing remain represented as independent layers for future approved slices.

## Tech Stack

Java 26, Spring Boot 4.1, Spring MVC, Bean Validation, Actuator, Spring Boot Test, Maven Wrapper and jar packaging.

## Getting Started

```bash
./mvnw clean test
./mvnw spring-boot:run
```

Open [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) and [http://localhost:8080/actuator/info](http://localhost:8080/actuator/info).

## Available Commands

```bash
./mvnw clean test
./mvnw clean package
./mvnw spring-boot:run
```

## Project Structure

```text
src/main/java/com/nexa/api/                  # Application and bounded contexts
src/main/java/com/nexa/api/shared/presentation # Correlation and Problem Details
src/main/java/com/nexa/api/catalogmanagement/infrastructure/seed # Seed authority
src/main/resources/seed/catalog/             # Byte-exact canonical seed and checksum
src/test/java/com/nexa/api/                  # HTTP and seed integrity tests
docs/releases/                               # Versioned release notes
```

## Current Status

v0.2.0 provides the shared HTTP/error foundation, generated build metadata, immutable catalog seed loader/validator and exact canonical seed resource. It does not claim business endpoints, persistence, security or production integration.

## Out of Scope

Authentication, authorization, tenant runtime, PostgreSQL, JPA, migrations, catalog REST, business endpoints, frontend integration and deployment.

## Roadmap

Future vertical slices require explicit contracts, identity, tenant isolation, persistence decisions and runtime evidence.
