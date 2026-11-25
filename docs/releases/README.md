# API release notes

Use [GitHub Releases](https://github.com/nexa-suite/api/releases) to find shipped
versions and their signed tags. The notes here describe API changes and setup.

## Releases

| Release | Summary |
|---|---|
| [v3.0.3](./v3.0.3.md) | Restores Buyer documents, payments and live updates, and fills in Sales and Warehouse cloud routes |
| [v3.0.2](./v3.0.2.md) | Connects Buyer checkout, Warehouse preparation, dispatch and receipt to the cloud database |
| [v3.0.1](./v3.0.1.md) | Restores Buyer order reads and delivery tracking |
| [v3.0.0](./v3.0.0.md) | Adds mobile terms, notifications and the cloud routes for ICISA validation |
| [v2.0.3](./v2.0.3.md) | Swagger navigation and optional cloud inventory and picking reads |
| [v2.0.2](./v2.0.2.md) | Swagger presentation and patch compatibility checks |
| [v2.0.1](./v2.0.1.md) | Published source patch: bounded Tenant-routed customer, catalog/SKU and notification capabilities; [release](https://github.com/nexa-suite/api/releases/tag/v2.0.1) |
| [v2.0.0](./v2.0.0.md) | Published source release: five-business-day delivery rule, Buyer password recovery and reviewed contract migration; [release](https://github.com/nexa-suite/api/releases/tag/v2.0.0) |
| [v1.2.0](./v1.2.0.md) | NEXA Academic Stable Checkpoint; [published release](https://github.com/nexa-suite/api/releases/tag/v1.2.0) |
| [v1.1.0](./v1.1.0.md) | Tenant-scoped business database routing and local suite review; [published release](https://github.com/nexa-suite/api/releases/tag/v1.1.0) |
| [v1.0.0](./v1.0.0.md) | First stable-version API boundary with scoped local-fixture and technical-gauge fixes |
| [v0.21.0](./v0.21.0.md) | Published academic technical checkpoint after v0.20.0; verify [publication](https://github.com/nexa-suite/api/releases/tag/v0.21.0) before consumption |
| [v0.20.0](./v0.20.0.md) | Operations Mobile server contracts and deployment boundary; verify [publication](https://github.com/nexa-suite/api/releases/tag/v0.20.0) before consumption |
| [v0.19.0](./v0.19.0.md) | Module/persistence boundary closure and transport/dependency hardening; verify [publication](https://github.com/nexa-suite/api/releases/tag/v0.19.0) before consumption |
| [v0.18.0](./v0.18.0.md) | Consumer-stable API closure and cross-client contracts |
| [v0.17.0](./v0.17.0.md) | Mobile V1 Core Contracts |
| [v0.16.1](./v0.16.1.md) | Backend V1 Freeze-Closure Patch |
| [v0.16.0](./v0.16.0.md) | Backend V1 Closure and Mobile Readiness |
| [v0.15.0](./v0.15.0.md) | Fulfillment and Financial Completion |
| [v0.14.0](./v0.14.0.md) | Commercial and Inventory Core |
| [v0.13.0](./v0.13.0.md) | Backend Foundation Closure and release hardening |
| [v0.12.0](./v0.12.0.md) | Tenant onboarding draft persistence foundation |
| [v0.11.0](./v0.11.0.md) | PRE-V1 Architecture & Governance Foundation API baseline |
| [v0.10.0](./v0.10.0.md) | Functional convergence continuation API baseline |
| [v0.9.0](./v0.9.0.md) | Operations stabilization and public contact request foundation |
| [v0.8.0](./v0.8.0.md) | Consolidated commercial, warehouse, logistics and buyer delivery API |
| [v0.6.0](./v0.6.0.md) | Consolidated identity, tenant administration, secured Catalog and commercial request foundation |
| [v0.4.0](./v0.4.0.md) | Catalog query REST contract, secure boundary and modern runtime |
| [v0.3.0](./v0.3.0.md) | Catalog Management domain foundation and seed mapping |
| [v0.2.0](./v0.2.0.md) | Spring Boot runtime foundation, correlation and seed integrity |
| [v0.1.0](./v0.1.0.md) | Initial modular monolith repository baseline |

## Release-lineage gaps

| Version | Classification | Evidence and meaning |
|---|---|---|
| `v0.5.0` | INTERNAL_PREPARATION_ONLY | Release-preparation commits exist; no public tag, GitHub Release or merged release PR. |
| `v0.7.0` | INTERNAL_PREPARATION_ONLY | Release-preparation commit exists; no public tag, GitHub Release or merged release PR. |

The public release history skips `v0.5.0` and `v0.7.0`. Git history retains their preparation commits.
