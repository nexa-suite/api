# Nexa API documentation index

API documentation describes implementation mapping, HTTP contracts, runtime
behavior, security implementation, database implementation, tests and release
evidence. Nexa Blueprint remains the canonical Product, Domain and accepted
architecture source: [nexa-suite/blueprint](https://github.com/nexa-suite/blueprint).

## Architecture and module map

- [Canonical bounded-context implementation map](./architecture/bounded-context-module-map.md)
- [Logical layer boundaries](./architecture/logical-layering.md)
- [Architecture decisions](./architecture/decisions/README.md)
- [Context implementation notes](./architecture/bounded-contexts/)

The eleven business context roots are listed in the module map. `edge`,
`bootstrap` and `shared` are technical modules, not additional business
contexts.

## HTTP and OpenAPI contracts

- [OpenAPI instructions and compatibility checks](./openapi/README.md)
- [Committed OpenAPI snapshot](./openapi/openapi.json)

## Security implementation

- [Current-schema RLS audit](./security/rls-current-schema-audit.md)

- [Authentication](./security/authentication.md)
- [Authorization](./security/authorization.md)
- [Tenant resolution](./security/tenant-resolution.md)
- [RLS scope classification](./security/rls-scope-classification.md)
- [Runtime database role](./security/runtime-database-role.md)
- [Threat models](./security/threat-models.md)
- [Security notifications and outbox](./security-notification-outbox.md)

## Database and resources

- [Flyway migration history](../src/main/resources/db/migration/)
- [Deterministic seed resources](../src/main/resources/seed/)
- [Business XML and XSD schemas](../src/main/resources/schemas/)

Migration history, seed artifacts and schemas remain source evidence. No
structural refactor should rewrite them.

## Runtime and local development

- [Operations overview](../ops/README.md)
- [Compose runtime details](../ops/compose/README.md)
- [Local setup and verification scripts](../scripts/README.md)

## Testing and release evidence

- [Testing notes](./testing/)
- [API structure differential — 2026-09-24](./verification/api-structure-differential-2026-09-24.md)
- [Release notes](./releases/README.md)
- [Repository changelog](../CHANGELOG.md)
- [Historical audits](./audits/)

## Domain foundation notes

- [API domain foundation archive](./domain/README.md)
