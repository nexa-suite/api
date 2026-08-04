CREATE TABLE IF NOT EXISTS iam.workspace_preview_throttle_bucket (
    bucket_key_hash CHAR(64) PRIMARY KEY,
    workspace_slug VARCHAR(80) NOT NULL,
    client_key_hash CHAR(64) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_workspace_preview_throttle_key CHECK (bucket_key_hash ~ '^[0-9a-f]{64}$' AND client_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_workspace_preview_throttle_count CHECK (request_count >= 0)
);
CREATE INDEX IF NOT EXISTS ix_workspace_preview_throttle_expiry ON iam.workspace_preview_throttle_bucket (updated_at);

-- The reserved roles are still closed-vocabulary RoleDefinitions. Their permissions
-- are completed here so runtime resolution has one persisted authority.
INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, p.permission_key
FROM tenant_management.role_definition r
JOIN tenant_management.permission_definition p ON p.permission_key = ANY (CASE r.code
    WHEN 'company_owner' THEN ARRAY[
        'warehouse.read','warehouse.location.manage','inventory.read','inventory.receive','inventory.adjust',
        'inventory.reserve','inventory.release','inventory.waste','fulfillment.read','fulfillment.manage',
        'logistics.read','dispatch.read','dispatch.assign','dispatch.schedule','dispatch.start_route',
        'dispatch.temperature','dispatch.incident','dispatch.reprogram','dispatch.complete','logistics.analytics.read',
        'document.read','document.generate','document.regenerate','document.upload','document.download',
        'payment.read','payment.create','payment.refund','payment.reconcile','client.credit.manage',
        'notification.read','notification.manage_preferences'
    ]::varchar[]
    ELSE ARRAY[]::varchar[]
END)
WHERE r.tenant_id IS NULL
  AND r.code = 'company_owner'
ON CONFLICT (role_id, permission_key) DO NOTHING;

DELETE FROM tenant_management.role_permission
WHERE role_id = (SELECT id FROM tenant_management.role_definition WHERE tenant_id IS NULL AND code = 'sales')
  AND permission_key IN ('catalog.product.manage','catalog.taxonomy.manage','catalog.price.manage');
DELETE FROM tenant_management.role_permission
WHERE role_id = (SELECT id FROM tenant_management.role_definition WHERE tenant_id IS NULL AND code = 'logistics')
  AND permission_key = 'catalog.promotion.manage';

CREATE OR REPLACE FUNCTION tenant_management.bump_authorization_memberships(role_uuid UUID)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO tenant_management.membership_authorization_state
        (membership_id, tenant_id, workspace_id, authorization_version, updated_at)
    SELECT a.membership_id, a.tenant_id, a.workspace_id, 1, current_timestamp
    FROM tenant_management.membership_role_definition a
    WHERE a.role_id = role_uuid
    ON CONFLICT (membership_id) DO UPDATE
        SET authorization_version = tenant_management.membership_authorization_state.authorization_version + 1,
            updated_at = current_timestamp;
END;
$$;

CREATE OR REPLACE FUNCTION tenant_management.bump_role_permission_authorization()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM tenant_management.bump_authorization_memberships(COALESCE(NEW.role_id, OLD.role_id));
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS role_permission_authorization_version ON tenant_management.role_permission;
CREATE TRIGGER role_permission_authorization_version
    AFTER INSERT OR UPDATE OR DELETE ON tenant_management.role_permission
    FOR EACH ROW EXECUTE FUNCTION tenant_management.bump_role_permission_authorization();

DROP TRIGGER IF EXISTS membership_role_definition_authorization_version ON tenant_management.membership_role_definition;
CREATE TRIGGER membership_role_definition_authorization_version
    AFTER INSERT OR UPDATE OR DELETE ON tenant_management.membership_role_definition
    FOR EACH ROW EXECUTE FUNCTION tenant_management.bump_role_permission_authorization();

-- Selective RLS. Application requests set app.current_tenant_id/app.current_workspace_id
-- on the active connection; table owners remain responsible for controlled workers.
ALTER TABLE sales.client_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.client_account_address ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.purchase_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.sales_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.purchase_request_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_documents.business_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_documents.evidence_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.credit_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.receivable ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.payment_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE payments.receivable_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications.inbox_item ENABLE ROW LEVEL SECURITY;

DO $$
DECLARE
    target regclass;
    policy_name TEXT;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'sales.client_account'::regclass,
        'sales.client_account_address'::regclass,
        'sales.purchase_request'::regclass,
        'sales.sales_order'::regclass,
        'sales.purchase_request_draft'::regclass,
        'business_documents.business_document'::regclass,
        'business_documents.evidence_object'::regclass,
        'payments.credit_account'::regclass,
        'payments.receivable'::regclass,
        'payments.payment'::regclass,
        'payments.payment_attempt'::regclass,
        'payments.receivable_allocation'::regclass,
        'notifications.inbox_item'::regclass
    ] LOOP
        policy_name := replace(target::text, '.', '_') || '_tenant_workspace_scope';
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', policy_name, target);
        EXECUTE format('CREATE POLICY %I ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))', policy_name, target);
    END LOOP;
END;
$$;
