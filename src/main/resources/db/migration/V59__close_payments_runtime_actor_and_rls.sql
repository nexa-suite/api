-- V59 closes the remaining service-foundation boundaries. V1-V58 are immutable.

-- Payment provider secrets are response-only. Existing plaintext values are
-- removed before the column is dropped so a database upgrade cannot retain them.
UPDATE payments.payment SET client_secret = NULL WHERE client_secret IS NOT NULL;
ALTER TABLE payments.payment DROP COLUMN IF EXISTS client_secret;
ALTER TABLE payments.payment
    ADD COLUMN IF NOT EXISTS bank_transfer_reference VARCHAR(160),
    ADD COLUMN IF NOT EXISTS bank_transfer_proof_evidence_id UUID,
    ADD COLUMN IF NOT EXISTS reviewed_by_membership_id UUID,
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS review_reason VARCHAR(1000);

CREATE INDEX IF NOT EXISTS ix_payment_bank_transfer_review
    ON payments.payment (tenant_id, workspace_id, method, status, reviewed_at, id);

-- The workflow identity is a non-interactive, stable technical principal. It
-- is scoped through one membership per workspace and receives only the
-- capabilities required by asynchronous service handoffs.
INSERT INTO iam.user_account
    (id, email, normalized_email, username, normalized_username, display_name,
     preferred_language, status, created_at, updated_at, version)
VALUES
    ('11111111-1111-4111-8111-111111111111', 'nexa-automation@system.invalid',
     'nexa-automation@system.invalid', 'NEXA_AUTOMATION', 'nexa_automation',
     'NEXA_AUTOMATION', 'es', 'ACTIVE', current_timestamp, current_timestamp, 0)
ON CONFLICT (id) DO UPDATE SET display_name = EXCLUDED.display_name,
    status = 'ACTIVE', updated_at = current_timestamp;

INSERT INTO tenant_management.role_definition
    (id, tenant_id, workspace_id, code, name, description, role_type, status,
     created_by_membership_id, created_at, updated_at, version)
VALUES
    ('22222222-2222-4222-8222-222222222222', NULL, NULL, 'system_workflow',
     'Nexa system workflow',
     'Non-interactive technical actor for idempotent cross-context handoffs',
     'SYSTEM_RESERVED', 'ACTIVE', NULL, current_timestamp, current_timestamp, 0)
ON CONFLICT (id) DO UPDATE SET status = 'ACTIVE', updated_at = current_timestamp;

INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT '22222222-2222-4222-8222-222222222222'::uuid, permission_key
FROM tenant_management.permission_definition
WHERE permission_key IN (
    'sales.purchase_request.read', 'sales.order.read', 'sales.order.create_manual',
    'warehouse.read', 'inventory.reserve', 'inventory.release', 'fulfillment.read',
    'fulfillment.manage', 'logistics.read', 'dispatch.read', 'dispatch.assign',
    'dispatch.schedule', 'dispatch.start_route', 'dispatch.temperature',
    'dispatch.incident', 'dispatch.reprogram', 'dispatch.complete',
    'document.read', 'document.generate', 'document.download', 'payment.read', 'payment.reconcile',
    'notification.read'
)
ON CONFLICT (role_id, permission_key) DO NOTHING;

ALTER TABLE tenant_management.workspace_membership DROP CONSTRAINT IF EXISTS ck_membership_type;
ALTER TABLE tenant_management.workspace_membership
    ADD CONSTRAINT ck_membership_type CHECK (membership_type IN ('INTERNAL', 'BUYER', 'SYSTEM_WORKFLOW'));

INSERT INTO tenant_management.workspace_membership
    (id, workspace_id, user_id, membership_type, status, created_at, updated_at, version)
SELECT md5('nexa-system-workflow:' || w.id::text)::uuid, w.id,
       '11111111-1111-4111-8111-111111111111'::uuid, 'SYSTEM_WORKFLOW',
       'ACTIVE', current_timestamp, current_timestamp, 0
FROM tenant_management.workspace w
ON CONFLICT (workspace_id, user_id) DO UPDATE SET membership_type = 'SYSTEM_WORKFLOW', status = 'ACTIVE', updated_at = current_timestamp;

INSERT INTO tenant_management.membership_role_definition
    (membership_id, tenant_id, workspace_id, role_id, assigned_at)
SELECT m.id, w.tenant_id, w.id, '22222222-2222-4222-8222-222222222222'::uuid, current_timestamp
FROM tenant_management.workspace_membership m
JOIN tenant_management.workspace w ON w.id = m.workspace_id
WHERE m.user_id = '11111111-1111-4111-8111-111111111111'::uuid
ON CONFLICT (membership_id, role_id) DO NOTHING;

INSERT INTO tenant_management.membership_authorization_state
    (membership_id, tenant_id, workspace_id, authorization_version, updated_at)
SELECT m.id, w.tenant_id, w.id, 0, current_timestamp
FROM tenant_management.workspace_membership m
JOIN tenant_management.workspace w ON w.id = m.workspace_id
WHERE m.user_id = '11111111-1111-4111-8111-111111111111'::uuid
ON CONFLICT (membership_id) DO NOTHING;

-- Refund is not a V1 command. Remove its advertised persisted capability until
-- a complete refund aggregate and provider workflow exists.
DELETE FROM tenant_management.role_permission WHERE permission_key = 'payment.refund';
DELETE FROM tenant_management.permission_definition WHERE permission_key = 'payment.refund';

-- FORCE makes the policy apply to the owner as well as the restricted runtime
-- role. Empty or missing request scope therefore returns no tenant data.
ALTER TABLE sales.client_account FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.client_account_address FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.purchase_request FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.sales_order FORCE ROW LEVEL SECURITY;
ALTER TABLE sales.purchase_request_draft FORCE ROW LEVEL SECURITY;
ALTER TABLE business_documents.business_document FORCE ROW LEVEL SECURITY;
ALTER TABLE business_documents.evidence_object FORCE ROW LEVEL SECURITY;
ALTER TABLE business_documents.object_storage_object FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.credit_account FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.receivable FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.payment FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.payment_attempt FORCE ROW LEVEL SECURITY;
ALTER TABLE payments.receivable_allocation FORCE ROW LEVEL SECURITY;
ALTER TABLE notifications.inbox_item FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT USAGE ON SCHEMA tenant_management, sales, catalog_management, warehouse,
            logistics, business_documents, payments, notifications, integration TO nexa_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenant_management,
            sales, catalog_management, warehouse, logistics, business_documents,
            payments, notifications, integration TO nexa_runtime;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA tenant_management, sales,
            catalog_management, warehouse, logistics, business_documents, payments,
            notifications, integration TO nexa_runtime;
    END IF;
END;
$$;
