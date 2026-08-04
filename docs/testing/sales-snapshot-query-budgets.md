# Sales snapshot query budgets

`SalesSnapshotQueryBudgetIT` executes the persistent Catalog and canonical SKU ACLs against real PostgreSQL with a counting `JdbcTemplate`.

The same batch paths are exercised for 1, 10 and 50 requested lines. Catalog snapshot lookup is bounded to the same maximum query count for every line count (the current path is at most four queries: product rows, availability batch, promotion batch and promotion rules when applicable). SKU snapshot lookup is one query for every line count, including the current price lateral join.

Run the evidence with:

```sh
./mvnw -Dtest=SalesSnapshotQueryBudgetIT -Dnexa.integration.enabled=true test
```

The test fails if query count grows with line count or if a SKU batch lookup performs more than one database query.
