# Nexa canonical bounded-context map

Status: v0.19 canonical implementation mapping. The immutable `BC01`–`BC11`
identifiers and names below follow the accepted context map; Java package roots
remain implementation evidence.

Nexa has exactly eleven business bounded contexts. `edge`, `bootstrap` and
`shared` are technical modules, not bounded contexts. `edge` owns inbound HTTP,
security, Problem Details and streaming composition. `bootstrap.runtime` owns
runtime-wide configuration and worker composition; `bootstrap.local` owns
local-only bootstrap. `shared` contains framework-neutral technical contracts
and primitives. Database schema names such as `warehouse`, `logistics` and
`payments` are physical storage names and do not define domain ownership.

| Canonical context | API module root | Current physical schema(s) | Ownership boundary |
|---|---|---|---|
| BC-01 Tenant & Access Governance | `tenantaccessgovernance` (`iam` and `tenantmanagement` technical subpackages) | `iam`, `tenant_management` | tenant, workspace, membership, identity and authorization |
| BC-02 Customer & Buyer Relationships | `customerbuyerrelationships` | `sales` legacy tables | customer account, buyer relationship and address |
| BC-03 Catalog & Commercial Policy | `catalogcommercialpolicy` | `catalog_management` | catalog, SKU, price and commercial policy |
| BC-04 Sales Commitment | `salescommitment` | `sales` | Purchase Request, Commercial Commitment and Sales Order |
| BC-05 Inventory Availability | `inventoryavailability` | `warehouse` | sellable availability, backing, physical allocation, lots and FEFO |
| BC-06 Fulfillment & Delivery | `fulfillmentdelivery` | `logistics` | fulfillment, picking, dispatch, delivery, attempts and evidence |
| BC-07 Credit & Receivables | `creditreceivables` | `payments` legacy physical placement | credit, receivable, application and financial adjustment |
| BC-08 Payments | `payments` | `payments` | provider-neutral Payment and provider/reconciliation lifecycle |
| BC-09 Business Documents | `businessdocuments` | `business_documents` | issued document and evidence metadata |
| BC-10 Notifications | `notifications` | `notifications`, `tenant_management.notification_preference` | notification candidate, channel delivery and retry |
| BC-11 Business Traceability | `businesstraceability` | `audit`; shared technical events use `integration` | append-only business facts and timeline projection |

Important boundaries:

- BC07 is not a Payments subdomain. Its public contracts live under
  `creditreceivables.application.publicapi`; BC08 may call those contracts
  without importing BC07 aggregates.
- BC09 exposes `BusinessEvidenceQuery` for immutable evidence availability and
  `BusinessDocumentCommands` for durable payment-receipt generation requests;
- BC08 does not write document tables directly.
- BC05 owns physical stock responsibility. BC06 owns execution and delivery
  outcome. `Inventory Backing` is not `Physical Allocation`.
- BC11 is not Security Audit. Security events remain in the BC01 IAM
  security boundary (`iam.security_audit_event`); business facts use the
  BC11 traceability boundary.
- `shared` provides named, framework-neutral error, context, event and runtime
  metrics contracts; it owns no business aggregate or infrastructure adapter.

For v0.19, each migrated business table has one explicit SQL owner in
[`canonical-sql-ownership.tsv`](canonical-sql-ownership.tsv). A physical schema
prefix does not override the per-table owner exceptions recorded there. A
consumer receives foreign facts through the owner's narrow public API; runtime
boundary composition adapts typed facts without issuing business SQL. The closure
and its source-level evidence are recorded in
[`v0.19-boundary-closure.md`](v0.19-boundary-closure.md).

New migration tables require an explicit ownership row. Existing HTTP paths,
SQL schemas and released Java names remain compatibility surfaces where needed;
those names do not alter table ownership or authorize cross-context SQL.
