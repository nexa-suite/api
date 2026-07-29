# ADR-006: Seed-only query runtime

Status: Accepted for API `v0.4.0`

## Context

The catalog query task requires a deterministic local runtime and does not authorize database infrastructure. Adding persistence now would invent ownership and migration behavior before the bounded context and tenant model are approved.

## Decision

- Use the checksum-validated 50-item catalog seed as the only query source in `v0.4.0`.
- Load and validate the seed at startup through Infrastructure.
- Do not add JPA, JDBC, PostgreSQL, Flyway, Liquibase, repositories, entities or database containers to the modern API runtime.
- Keep legacy database orchestration isolated in the legacy Compose profile.

## Consequences

The modern API is deterministic and portable, but not a production persistence implementation. A future persistence decision must include tenant ownership, migration, consistency and authorization evidence.
