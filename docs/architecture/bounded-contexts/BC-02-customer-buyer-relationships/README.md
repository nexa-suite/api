# BC-02 — Customer & Buyer Relationships

- **Owner:** `com.nexa.api.customerbuyerrelationships`
- **Storage:** legacy `sales` customer-account, address and buyer-membership
  tables.
- **Owns:** customer account identity, buyer association and delivery/contact
  relationship data.
- **Public contracts:** customer account and buyer relationship query/command
  ports consumed by Sales Commitment and API presentation.
- **v0.15 relation:** provides the commercial party and buyer scope; fulfillment
  snapshots destination data and does not re-own customer records.
- **Excludes:** Sales Commitment pricing/order lifecycle and BC-01 membership
  authorization.

Legacy SQL location is compatibility storage, not the bounded-context name.
