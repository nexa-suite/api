# BC-04 — Sales Commitment

- **Owner:** `com.nexa.api.salescommitment`
- **Storage:** legacy `sales` Purchase Request, Commercial Commitment and Sales
  Order tables.
- **Owns:** buyer demand, commercial commitment, order confirmation, origin and
  immutable commercial snapshots.
- **Public contracts:** sales-order fulfillment snapshot/query and status-command
  ports used by BC-06; existing Purchase Request and commitment contracts.
- **v0.15 relation:** accepts `IN_FULFILLMENT`, partial-delivery and completed
  status facts without owning picking, stock or payment provider state.
- **Excludes:** physical allocation, delivery evidence and receivable ledger.

The `sales` schema remains a documented compatibility projection.
