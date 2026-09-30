# Warehouse object access verification

The accepted policy assigns explicit Warehouse grants to active internal WorkforceMemberships and intersects them with current permissions and Tenant/Workspace status. `tenant.role.assign` governs grant administration. No grant means denied Warehouse access; Buyer memberships cannot receive a grant. Authorization changes increment the target membership authorization version and preserve append-only administration facts.

BC-01 owns grants and BC-05 resolves Warehouse facts through a narrow public contract. V108 adds a forced-RLS Tenant/Workspace-scoped table without cross-context Warehouse SQL ownership. Warehouse lists, lot identifiers, receiving, stock operations and physical allocation commands enforce current grants. A revoked grant denies even an idempotent receipt replay. Warehouse availability filters quantities before summing; the new scoped read never includes another Warehouse.

Validation executed on JDK 25:

- `./mvnw verify -Dnexa.integration.enabled=true` on `a4dc692`: 594 tests, zero failures, errors or skips, packaged artifact produced. Required ClamAV and Stripe-compatible local providers were running.
- Focused `WarehouseObjectAccessIT` and `WarehouseSafetyStockTransferIT` after aggregate-read hardening: 9 tests, zero failures, errors or skips. Coverage includes Warehouse A/B isolation, aggregate quantity reduction after revocation, unauthorized administration, inactive/Buyer grant rejection, concurrent grant and replay.
- Runtime-generated OpenAPI matched its snapshot; compatibility against `origin/develop` passed.

Android stock presentation, physical-device scanner evidence, Product Acceptance and Production Readiness remain separate gates. This checkpoint does not establish them.
