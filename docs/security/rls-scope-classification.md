# AS-IS RLS scope classification

Status: API v0.16.0 implementation registry; runtime certification remains a PostgreSQL gate. This is the current API schema registry. Blueprint TARGET table names are not substituted for the AS-IS Flyway inventory.

The executable inventory is `ModernPostgresMigrationTests.assertTenantWorkspaceRls(...)` plus the schema/table assertions in `flywayCreatesOnlyTheModernIdentityAndTenantSchemasWithRequiredTables`. A table is not considered protected merely because a repository adds a tenant predicate.

## TENANT_SCOPED_RLS / WORKSPACE_SCOPED_RLS

Forced PostgreSQL policies with both `USING` and `WITH CHECK` exist for the high-risk business tables listed below. Every listed table has direct `tenant_id` and `workspace_id` columns and is checked by fresh-migration tests and the non-owner runtime isolation test.

- `sales`: `client_account`, `client_account_address`, `client_account_membership`, `commercial_commitment`, `commercial_commitment_line`, `manual_sales_order_draft`, `manual_sales_order_draft_idempotency`, `manual_sales_order_draft_line`, `purchase_request`, `purchase_request_event`, `idempotency_record`, `idempotency_response`, `purchase_request_draft`, `purchase_request_draft_destination`, `purchase_request_draft_idempotency`, `purchase_request_draft_line`, `purchase_request_draft_route`, `purchase_request_draft_warehouse_selection`, `sales_order`, `sales_order_event`.
- `payments`: `credit_account`, `credit_reservation`, `payment`, `payment_attempt`, `payment_event`, `payment_reconciliation_case`, `reconciliation_refund_idempotency`, `receivable`, `receivable_allocation`, `financial_adjustment`, `financial_ledger_entry`, `refund_credit_obligation`, `receivable_application`.
- `business_documents`: `business_document`, `evidence_object`, `object_storage_object`.
- `notifications`: `inbox_item`.
- `warehouse`: `warehouse`, `storage_zone`, `inventory_lot`, `stock_movement`, `inventory_event`, `inventory_reservation`, `command_idempotency`, `warehouse_service_configuration`, `selection_snapshot`, `inventory_lot_disposition`, `inventory_temperature_evaluation`, `safety_stock_policy`, `inventory_transfer`, `inventory_backing`, `inventory_backing_line`, `inventory_backing_position`, `physical_allocation`, `physical_allocation_line`, `physical_allocation_event`, `physical_allocation_command_idempotency`.
- `logistics`: `dispatch_number_counter`, `dispatch_order`, `dispatch_event`, `command_idempotency`, `proof_of_delivery`, `temperature_reading`, `delivery_incident`, `operational_handoff_note`, `delivery_attempt`, `delivery_attempt_line`, `continuation_delivery`, `continuation_delivery_line`, `fulfillment`, `fulfillment_line`, `fulfillment_command_idempotency`, `fulfillment_event`, `picking_result`, `picking_result_line`, `picking_discrepancy`, `picking_discrepancy_resolution`, `delivery`, `delivery_command_idempotency`, `delivery_assignment`, `delivery_quantity_outcome`, `delivery_event`, `temperature_evidence`, `temperature_excursion`, `proof_of_delivery_addendum`.

`TENANT_SCOPED_RLS` is the database enforcement category. `WORKSPACE_SCOPED_RLS` is the effective policy shape: a tenant is never visible without its workspace binding. The policy is fail-closed when either setting is absent or mismatched.

## TENANT_SCOPED_RLS — reviewed AS-IS exceptions

The following current tables carry tenant/workspace ownership but are not in the forced-policy set. Their exception is explicit, not accidental: they are accessed through scope-bound application ports and composite foreign keys, while their parent-derived child rows do not carry an independent policy key. They remain release evidence and are not described as production RLS certification.

- `audit.event` and `iam.security_audit_event` — append-only audit records; viewers require an explicit tenant/workspace predicate and the IAM writer receives the validated access scope.
- `tenant_management` workspace configuration and membership administration tables — workspace foreign keys plus access-context authorization; bootstrap and governance jobs require an explicit system path.
- `catalog_management` tenant catalog/pricing/promotion tables — current catalog ownership/query boundary is preserved; no new shared-versus-tenant interpretation is invented in v0.14.
- child tables such as reservation lines, allocation lines, draft lines, and document request rows — parent scope and composite foreign keys are the current AS-IS boundary; workers reconstruct parent scope before mutation.

These exceptions are the retained v0.14 AS-IS boundary inside the v0.15 schema. Closing them requires a separate additive policy and worker-scope design; it must not be inferred from Blueprint TARGET models.

## TENANT_SYSTEM_QUEUE

These queues carry tenant/workspace identity and are processed by scoped system workers. Their cross-workspace read is an intentional worker exception; each effect is fenced and each scoped write executes after `RlsRequestScope` reconstruction.

- `integration.outbox_event` and `integration.inbox_event` — canonical at-least-once event/inbox backbone.
- `payments.stripe_event_inbox` — signed webhook inbox; tenant/workspace binding is mandatory for payment intents.
- `business_documents.document_generation_request` — durable renderer queue; the worker scopes by the request row before touching forced document data.
- `integration.change_event` — tenant/workspace change-feed retention and delivery metadata.

## GLOBAL_IDENTITY

`iam.user_account`, `iam.password_credential`, `iam.refresh_session`, `iam.authentication_failure`, `iam.password_reset_request`, and `iam.security_audit_event` are identity/security records. Authorization is enforced by the IAM boundary and security audit rules; these tables are not treated as generic tenant business data.

## GLOBAL_REFERENCE

`reference_data.department`, `reference_data.province`, `reference_data.district`, and `reference_data.road_type` are immutable shared reference data. They contain no tenant-owned business state.

## TECHNICAL_GLOBAL

`flyway_schema_history`, `iam.password_reset_throttle_bucket`, `iam.system_operator_throttle_bucket`, `iam.workspace_preview_throttle_bucket`, `iam.public_contact_request`, `iam.public_contact_throttle_bucket`, and `iam.security_notification_outbox` are technical/security infrastructure. They are guarded by restricted persistence paths, append-only or bounded-retention behavior, encrypted payloads where applicable, and stable identities. `iam.security_notification_outbox` has no tenant columns by design; it is not a business-data RLS bypass.

## NOT_APPLICABLE

No current API table is silently unclassified. Tables added by a future Flyway migration must be assigned one of these categories and added to the migration/architecture tests in the same change. Blueprint logical TARGET models remain separate evidence and do not alter this AS-IS registry.

## Runtime proof

`RlsRuntimeDatabaseIsolationIT` verifies missing scope, wrong tenant, wrong workspace, guessed foreign UUID, pooled-connection cleanup, stale claim fencing, and non-owner/non-BYPASSRLS runtime identity when PostgreSQL is available. `ModernPostgresMigrationTests` verifies the v0.16 forced-policy inventory, `USING`, `WITH CHECK`, lease columns, financial snapshots, and fresh PostgreSQL 18 construction. V91 temporarily removes `FORCE` only for its owner-controlled transactional compatibility backfill, then restores it before commit; this requires real PostgreSQL execution for certification.
