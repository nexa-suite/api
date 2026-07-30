# ADR-005: Three-tier modern data architecture

Status: Accepted

## Context

TASK-NEXA-005 adds durable IAM and workspace scope while Platform and Portal remain browser clients. PostgreSQL must not become a client dependency or a second business authority.

## Decision

Use Client, Server and Data deployment tiers. Angular clients call Spring Boot over HTTP. Spring Boot is the only application allowed to reach PostgreSQL. PostgreSQL uses private Docker network `nexa-modern-data`, with `postgres:18.4-alpine` and Flyway-managed schemas `iam` and `tenant_management`.

## Alternatives considered

- Browser access to PostgreSQL: rejected because credentials and authorization would leave Server Tier.
- Separate service per bounded context: rejected because TASK-NEXA-005 targets a modular monolith with explicit package boundaries.
- Seed-only runtime: rejected for IAM and tenant state because identity and membership require durable constraints.

## Consequences

The API owns transactions, authorization and tenant resolution. Local startup requires database configuration. Catalog remains shared seed reference data and is not claimed tenant-isolated.

## Risks

Incorrect Compose networks or runtime configuration could expose Data Tier. Migration drift could block startup. Health checks must include database and Flyway readiness.

## Evidence required

Flyway empty-database and repeat-start tests, Docker network inspection, API health response, and browser network evidence showing no database connection.

## Review trigger

Review when Catalog becomes tenant-assortment data, when a new operational context receives persistence, or when deployment topology changes.
