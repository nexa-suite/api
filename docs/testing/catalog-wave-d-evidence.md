# Wave D: catalog concurrency and query-count evidence

This evidence uses the existing real PostgreSQL/Testcontainers integration support. It does not add a query-count dependency or change production code.

## Covered behavior

- Two real HTTP requests create the same overlapping `product_price` period. The PostgreSQL exclusion constraint must produce exactly `1 x 201` and `1 x 409` (`CATALOG_PRICE_OVERLAP`), with no `500`.
- Two real HTTP requests activate the same promotion with the same `If-Match: "0"`. The optimistic version update must produce exactly `1 x 200` and `1 x 409` (`CONCURRENCY_CONFLICT`), with final version `1`.
- The persistent catalog search is executed with page sizes `1`, `10` and `25`. The test-only `JdbcTemplate` counter records four query operations for each size: page rows, total count, availability batch and promotion batch. The assertion is constant across sizes and therefore guards against per-item query growth.

## Reproduction

Prerequisites: Java 25, Maven wrapper, and Docker available to Testcontainers.

```bash
cd /Users/diegosandoval284/Documents/nexa/09-repositories/nexa-suite/api
./mvnw -Dnexa.integration.enabled=true -Dtest=CatalogWaveDConcurrencyIT test
```

The class is disabled unless `nexa.integration.enabled=true`; without Docker the integration evidence cannot run.

The query count is a count of `JdbcTemplate` query operations in the existing adapters, not a new production metric or a database extension. The test intentionally exercises the adapter directly with the same PostgreSQL `DataSource` used by the integration context.

## Current run

The owning JDBC adapter normalizes the PostgreSQL `NUMERIC(19,4)` scale at the response boundary. The price fixture selects the effective tenant currency, so the result is independent of whether an earlier integration class created regional settings. The real PostgreSQL run now passes all three tests: price creation `1 x 201` plus `1 x 409` (`CATALOG_PRICE_OVERLAP`), promotion activation `1 x 200` plus `1 x 409` (`CONCURRENCY_CONFLICT`), and query-count `4/4/4` for page sizes `1/10/25`. Surefire: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.
