# ICISA catalog reference and provisional pricing

This bundle documents catalog-reference data and local demonstration pricing for the ICISA workspace. It is an evidence projection; it does not change API behavior or configure application startup.

## Included files

- `catalog-102.csv` — 102 catalog rows: 50 preserved catalog records and 52 provisional references. The new rows include the API-confirmed provisional PEN amounts and use `priceStatus=PROVISIONAL_REFERENCE`.
- `provisional-reference-price-plan.csv` — the 52 authorized reference amounts, source code, catalog anchors, and item-specific estimation rationale.
- `import-icisa.sql` — the scoped catalog import script. Its historical price-count assertions are described below; this file is unchanged.
- `source-sha256s.txt` — relative-path source hashes for the catalog reference and product assets.

## Catalog data and assets

For the 50 preserved rows, `taxCategory` comes from the corresponding `sellable_sku.tax_category` value in the supplied pre-import snapshot. The 52 provisional rows use `UNSPECIFIED`, matching the explicit tax value in the catalog import SQL. This is the SQL default, not a newly confirmed tax classification.

`availableStock` is blank for all rows because stock is inventory-owned and was not supplied by the catalog reference. The 52 provisional rows still have no GTIN or supplied weight values; the API price writes did not fill those product specifications. Their categories, storage classifications, SKU details, and labels remain provisional pending authoritative confirmation.

The 102 asset paths and SHA-256 values were checked against the local Complementary `catalog-reference/assets/products` files and the included source manifest. This verifies file presence and byte-level consistency only. It does not establish visual suitability, image rendering in an application, or media delivery from an API endpoint.

## Provisional demonstration prices

On 2026-10-03, the 52 previously unpriced SKUs received one local ICISA PEN reference price each through the authorized Catalog price API, with source code `PROVISIONAL_REFERENCE` and effective time `2026-10-03T06:02:09Z`. The authenticated account resolved to `COMPANY_OWNER` with `catalog:price:manage`. The write log records 52 successful writes and 52 idempotent replays; the post-write snapshot confirms 102 SKUs each have one active price, the original 50 price histories and current values are unchanged, and the 52 new products remain hidden from Buyer visibility.

The amounts are authorized for local demonstration only. They are estimates anchored to labels, pack sizes where specified, and existing ICISA catalog prices. No supplier or market quotations were used. They are not quotations, approved selling prices, or confirmation of SKU weights, packaging, or commercial specifications. Confirm those details with the relevant product and commercial owners before any offer or sale.

The exact plan is included above. The write log and before/after snapshots remain in the dated local evidence directory `artifacts/operations-mobile/2026-10-03/catalog-pricing/`; they are not copied into this API repository.

## SQL replay boundary

`import-icisa.sql` retains its historical postconditions requiring exactly 50 rows in each of `catalog_management.sku_price` and `catalog_management.product_price`. The 52 new reference prices were created through the API after the catalog import evidence was captured. Do not rerun this SQL against the current priced state as-is: its fixed price-count assertions need a separately reviewed next-stage adjustment. This documentation update did not execute or modify the SQL.

## Evidence boundary

The cited write and snapshot evidence is local to the ICISA environment. This bundle does not establish public deployment, API startup mapping, runtime image delivery, Buyer visibility for the new rows, commercial acceptance, or production readiness. Provisional values and specifications remain subject to authoritative owner confirmation.
