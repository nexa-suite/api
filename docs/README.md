# Nexa API documentation

Start with the [repository README](../README.md) to run the API and find the ICISA deployment. The [Nexa Blueprint](https://github.com/nexa-suite/blueprint) records the product and architecture decisions behind the implementation.

## Use the API

- [Read or generate the OpenAPI contract](openapi/README.md)
- [Browse the committed contract](openapi/openapi.json)
- [Authentication](security/authentication.md) and [authorization](security/authorization.md)
- [Buyer delivery dates](integration/buyer-delivery-lead-policy.md)

## Work on the backend

- [Context map](architecture/bounded-context-module-map.md), [logical layers](architecture/logical-layering.md), and [architecture decisions](architecture/decisions/README.md)
- [Database routing](architecture/tenant-database-routing-foundation.md) and [runtime database permissions](security/runtime-database-role.md)
- [Tenant resolution](security/tenant-resolution.md), [row-level security](security/rls-scope-classification.md), and [schema audit](security/rls-current-schema-audit.md)
- [Flyway migrations](../src/main/resources/db/migration/), [seed data](../src/main/resources/seed/), and [XML schemas](../src/main/resources/schemas/)

## Run and deploy

- [Local Compose runtime](../ops/compose/README.md) and [developer scripts](../scripts/README.md)
- [Runtime operations](../ops/README.md) and [cloud database routing](../ops/tenant-business-database-cloud.md)
- [Release notes](releases/README.md), [changelog](../CHANGELOG.md), and [release policy](../.github/RELEASE_POLICY.md)
