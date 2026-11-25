<div align="center">

<img src="./docs/assets/nexa.svg" alt="Nexa" width="240" />

# Nexa API

[![Latest Git tag](https://img.shields.io/github/v/tag/nexa-suite/api?sort=semver&style=flat-square&label=latest%20release)](https://github.com/nexa-suite/api/releases)

[Swagger](https://nexa-api-69bj.onrender.com/swagger-ui/index.html) · [Service status](https://nexa-api-69bj.onrender.com/actuator/health/readiness) · [Android apps](https://github.com/nexa-suite/mobile/releases/tag/v3.0.2)

</div>

Nexa API is the backend for our Buyer and Operations apps, Buyer Portal, and Platform. Buyers use it to browse the catalog, send purchase requests, and track deliveries. Sales, Warehouse, and Logistics use the same API to process requests, prepare orders, and record delivery.

## Try the ICISA deployment

- [Swagger UI](https://nexa-api-69bj.onrender.com/swagger-ui/index.html) lists the HTTP endpoints. Choose `complete` to see all documented routes.
- [API readiness](https://nexa-api-69bj.onrender.com/actuator/health/readiness) shows when the service is ready. Render Free can take a moment to start after sitting idle.
- [API v3.0.3](https://github.com/nexa-suite/api/releases/tag/v3.0.3) is the release used with the [Buyer and Operations APKs](https://github.com/nexa-suite/mobile/releases/tag/v3.0.2). The [v3.0.3 notes](docs/releases/v3.0.3.md) list the cloud routes included in that patch.

## Run locally

Use Java 25 and Docker with Compose. The project includes the Maven Wrapper. Keep the Platform and Buyer Portal checkouts next to the API if you want to start the full local stack.

```sh
./scripts/setup-local-environment.sh
```

The setup script prepares local configuration and starts the stack. The API listens on `http://127.0.0.1:8080`. With the local environment already configured, run `./ops/compose/scripts/modern-up.sh api-runtime` to start the API services. See the [Compose guide](ops/compose/README.md) for status, shutdown, storage, and local profiles. Keep the local environment file and signing keys outside Git.

## Run the tests

```sh
./mvnw clean test
./mvnw clean verify -Dnexa.integration.enabled=true
```

The second command runs the integration tests with PostgreSQL through Testcontainers. Keep Docker running for that command.

## How the code is organized

The API is a Java 25 and Spring Boot modular monolith backed by PostgreSQL. Flyway manages the schema. The server checks permissions and workspace scope and records the final outcome of business actions.

- `src/main/java/com/nexa/api` contains the business contexts. Each context separates HTTP handlers, application use cases, domain code, and persistence or provider adapters.
- `edge`, `bootstrap`, and `shared` contain HTTP security, application wiring, and common technical code.
- `src/main/resources` contains runtime configuration, migrations, and seed data. `src/test` contains unit and integration tests.
- `ops` and `scripts` hold the local runtime and deployment tooling.

The [context map](docs/architecture/bounded-context-module-map.md) links this implementation to the [Nexa Blueprint](https://github.com/nexa-suite/blueprint).

## Project links

- [Developer documentation](docs/README.md)
- [OpenAPI contract and compatibility checks](docs/openapi/README.md)
- [Release notes](docs/releases/README.md) and [changelog](CHANGELOG.md)
- [Contributing](.github/CONTRIBUTING.md) and [security](.github/SECURITY.md)
- [Buyer Portal](https://github.com/nexa-suite/portal), [Platform](https://github.com/nexa-suite/platform), [Website](https://github.com/nexa-suite/website), and [Mobile report](https://github.com/nexa-suite/mobile-report)
