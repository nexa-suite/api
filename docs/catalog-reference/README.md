# ICISA catalog reference handoff

This directory contains a reviewed catalog-reference projection and a scoped SQL import script for the ICISA workspace. It documents source metadata and import boundaries; it does not change API behavior or configure application startup.

## Included files

- `catalog-102.csv` — 102 catalog rows: 50 preserved from the supplied pre-import database snapshot and 52 provisional references derived from the Complementary catalog index and product assets.
- `import-icisa.sql` — a transaction-scoped SQL import script for the ICISA tenant/workspace. It is included for review; this documentation handoff did not execute it.
- `source-sha256s.txt` — the source manifest with relative paths for the catalog source material and product image files.

## Data interpretation

For the 50 preserved rows, `taxCategory` comes from the corresponding `sellable_sku.tax_category` value in the supplied pre-import snapshot. The 52 provisional rows show `UNSPECIFIED`, matching the explicit tax-category value assigned by `import-icisa.sql`; the source import plan itself leaves that source field blank. This makes the CSV value a projection of the SQL default, not a newly verified tax classification.

The 52 provisional rows have no price, GTIN, or supplied weight values. Their SQL insertion leaves GTIN and weight fields unset. `availableStock` is blank for all rows because stock is inventory-owned and is not supplied by this catalog reference. The 50 preserved rows retain their recorded price data.

The product asset references and SHA-256 values were checked against the local Complementary `catalog-reference/assets/products` files and the included source manifest. This verifies file presence and byte-level consistency only. It does not establish visual suitability, image rendering in an application, or media delivery from an API endpoint.

## Evidence boundary

The accompanying dated catalog-import evidence retains pre-import and post-import snapshots outside this API repository. Those snapshots are historical local database evidence and are not copied here. This handoff did not restart the API or verify startup loading, runtime mapping, public deployment, buyer visibility, or production acceptance. The catalog values and provisional classifications require the relevant product and commercial owners' confirmation before sale.
