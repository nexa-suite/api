# Nexa canonical bounded-context map

Status: canonical for API v0.16.1.  The `BC-*` identifiers and names below
match the Blueprint directories exactly.

Nexa has exactly eleven business bounded contexts.  `bootstrap` and `shared`
are technical composition modules, not bounded contexts.  Database schema
names such as `warehouse`, `logistics` and `payments` are legacy physical
storage names and do not define domain ownership.

| Canonical context | API module root | Current physical schema(s) | Ownership boundary |
|---|---|---|---|
| BC-01 Tenant & Access Governance | `tenantaccessgovernance` (`iam` and `tenantmanagement` technical subpackages) | `iam`, `tenant_management` | tenant, workspace, membership, identity and authorization |
| BC-02 Customer & Buyer Relationships | `customerbuyerrelationships` | `sales` legacy tables | customer account, buyer relationship and address |
| BC-03 Catalog & Commercial Policy | `catalogcommercialpolicy` | `catalog_management` | catalog, SKU, price and commercial policy |
| BC-04 Sales Commitment | `salescommitment` | `sales` | Purchase Request, Commercial Commitment and Sales Order |
| BC-05 Inventory Availability | `inventoryavailability` | `warehouse` | sellable availability, backing, physical allocation, lots and FEFO |
| BC-06 Fulfillment & Delivery | `fulfillmentdelivery` | `logistics` | fulfillment, picking, dispatch, delivery, attempts and evidence |
| BC-07 Credit & Receivables | `creditreceivables` | `payments` legacy schema during additive migration | credit, receivable, application and financial adjustment |
| BC-08 Payments | `payments` | `payments` | provider-neutral Payment and provider/reconciliation lifecycle |
| BC-09 Business Documents | `businessdocuments` | `business_documents` | issued document and evidence metadata |
| BC-10 Notifications | `notifications` | `notifications` | notification candidate, channel delivery and retry |
| BC-11 Business Traceability | `businesstraceability` | `audit`, `integration` | append-only business facts and timeline projection |

Important boundaries:

- BC-07 is not a Payments subdomain.  Its public contracts live under
  `creditreceivables.application.publicapi`; BC-08 may call those contracts
  without importing BC-07 aggregates.
- BC-09 exposes `BusinessEvidenceQuery` for immutable evidence availability and
  `BusinessDocumentCommands` for durable payment-receipt generation requests;
  BC-08 does not write document tables directly.
- BC-05 owns physical stock responsibility.  BC-06 owns execution and
  delivery outcome.  `Inventory Backing` is not `Physical Allocation`.
- BC-11 is not Security Audit.  Security events remain in the BC-01 IAM
  security boundary (`iam.security_audit_event`); business facts use the
  BC-11 traceability boundary.
- `shared` may provide framework/error primitives and transport composition;
  it must not become a business aggregate owner.

All v0.16 source changes must name the canonical context in its module root,
package documentation, public contract and test owner.  Existing HTTP paths,
SQL schemas and released Java names may remain as compatibility aliases when
changing them would break v0.14 contracts; the alias is documented here and
does not change ownership.
