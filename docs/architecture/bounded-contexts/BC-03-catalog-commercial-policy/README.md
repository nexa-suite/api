# BC-03 — Catalog & Commercial Policy

- **Owner:** `com.nexa.api.catalogcommercialpolicy`
- **Storage:** `catalog_management` plus reference data.
- **Owns:** catalog items, product families/variants, sellable SKUs, prices,
  promotions and commercial policy reads.
- **Public contracts:** sellable SKU, customer terms and pricing query ports.
- **v0.15 relation:** Sales Commitment owns immutable price/currency snapshots;
  fulfillment consumes those snapshots for final quantity adjustment pricing.
- **Excludes:** physical stock, inventory backing, payment execution and order
  fulfillment.

Catalog seed data remains infrastructure data, not a domain persistence entity.
