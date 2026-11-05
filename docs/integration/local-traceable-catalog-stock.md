# Local traceable catalog stock

This opt-in ICISA fixture receives synthetic quantities through the same
`POST /api/v1/inventory/inbound-receipts` contract used by Warehouse clients.
It preserves the catalog, existing lots and physical stock history. It creates
one explicitly named `LOCAL-TRACE-<SKU UUID>` lot per active SKU and storage
zones matching the SKU temperature bounds.

Run from the API repository after the current local API is ready:

```bash
node ops/scripts/seed-local-catalog-stock.mjs --plan
node ops/scripts/seed-local-catalog-stock.mjs --apply
```

The default synthetic quantity is 100 in each SKU's authoritative unit. An
explicit `--quantity` from 1 to 10000 and `--warehouse-id UUID` are supported.
The default Warehouse is `ICISA-COLD-01`; the Warehouse identity must be visible
to the configured local Warehouse operator. All active SKU pages are read.
Inactive SKUs are excluded. Missing SKU identity or unit stops preparation.

Only a running local-profile API at `127.0.0.1:8080`, local Docker socket and
matching ICISA development identities are accepted. Credentials and bearer
tokens are never printed or written to the receiving journal. The script does
not grant permissions, reset data or bypass the API's receiving rules.

Before sending each receipt, its exact payload and command identity are saved
in the ignored `.local/catalog-stock/` directory. A retry preserves dates,
quantity and the idempotency key. The script checks the receiving movement,
production/entry/expiry dates, SKU, Warehouse, zone and unit. Repeating the same
command must return the same lot and acknowledged received quantity. Current
on-hand stock may legitimately change after receipt; it is not the original
receiving quantity.

These are synthetic dates and temperatures, not observed sensor readings or
physical inventory. Existing bootstrap lots are retained; this fixture does
not retroactively manufacture receipt history for older stock. If a journal is
lost, a lot conflicts, or a SKU changes, preparation stops for reconciliation
instead of receiving duplicate stock. An exclusive journal lock prevents
concurrent runs. An interrupted process may leave that lock for explicit
reconciliation; it is never silently removed by another invocation.
