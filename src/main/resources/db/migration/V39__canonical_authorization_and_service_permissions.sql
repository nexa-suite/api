-- Canonical role-definition authority. membership_role_assignment remains only as a
-- read-only upgrade ledger so existing installations can be upgraded without loss.
INSERT INTO tenant_management.permission_definition
    (permission_key, permission_group, display_name, description, reserved)
VALUES
    ('document.read', 'DOCUMENTS', 'Read business documents', 'Read scoped business documents', TRUE),
    ('document.generate', 'DOCUMENTS', 'Generate business documents', 'Request document generation', TRUE),
    ('document.regenerate', 'DOCUMENTS', 'Regenerate business documents', 'Create a new document version', TRUE),
    ('document.upload', 'DOCUMENTS', 'Upload document evidence', 'Upload quarantined evidence', TRUE),
    ('document.download', 'DOCUMENTS', 'Download business documents', 'Download authorized private documents', TRUE),
    ('payment.read', 'PAYMENTS', 'Read payments', 'Read scoped payment attempts and receipts', TRUE),
    ('payment.create', 'PAYMENTS', 'Create payments', 'Create server-authoritative payment intents', TRUE),
    ('payment.refund', 'PAYMENTS', 'Refund payments', 'Request authorized refunds', TRUE),
    ('payment.reconcile', 'PAYMENTS', 'Reconcile payments', 'Reconcile bank and provider payments', TRUE),
    ('client.credit.manage', 'CLIENT_ACCOUNTS', 'Manage client credit', 'Manage credit limits and exposure policy', TRUE)
ON CONFLICT (permission_key) DO NOTHING;

DELETE FROM tenant_management.role_permission
WHERE role_id = (SELECT id FROM tenant_management.role_definition WHERE code = 'sales' AND tenant_id IS NULL)
  AND permission_key IN ('catalog.product.manage', 'catalog.taxonomy.manage', 'catalog.price.manage');

DELETE FROM tenant_management.role_permission
WHERE role_id = (SELECT id FROM tenant_management.role_definition WHERE code = 'logistics' AND tenant_id IS NULL)
  AND permission_key = 'catalog.promotion.manage';

INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, p.permission_key
FROM tenant_management.role_definition r
JOIN tenant_management.permission_definition p ON p.permission_key = ANY (CASE r.code
    WHEN 'company_owner' THEN ARRAY[
        'client.credit.manage','document.read','document.generate','document.regenerate',
        'document.upload','document.download','payment.read','payment.create','payment.reconcile'
    ]::varchar[]
    WHEN 'sales' THEN ARRAY['document.read','document.download','payment.read']::varchar[]
    WHEN 'warehouse' THEN ARRAY['document.read','document.upload']::varchar[]
    WHEN 'logistics' THEN ARRAY['document.read','document.upload']::varchar[]
    WHEN 'buyer' THEN ARRAY['document.read','document.download','payment.read','payment.create']::varchar[]
    ELSE ARRAY[]::varchar[]
END)
WHERE r.tenant_id IS NULL
ON CONFLICT (role_id, permission_key) DO NOTHING;

INSERT INTO tenant_management.membership_role_definition
    (membership_id, tenant_id, workspace_id, role_id, assigned_at)
SELECT a.membership_id, a.tenant_id, a.workspace_id, r.id, a.assigned_at
FROM tenant_management.membership_role_assignment a
JOIN tenant_management.role_definition r
  ON r.tenant_id IS NULL
 AND r.code = lower(replace(a.role, '_', '_'))
ON CONFLICT (membership_id, role_id) DO NOTHING;

INSERT INTO tenant_management.membership_authorization_state
    (membership_id, tenant_id, workspace_id, authorization_version, updated_at)
SELECT m.id, w.tenant_id, m.workspace_id, 0, current_timestamp
FROM tenant_management.workspace_membership m
JOIN tenant_management.workspace w ON w.id = m.workspace_id
WHERE m.status = 'ACTIVE'
ON CONFLICT (membership_id) DO NOTHING;

COMMENT ON TABLE tenant_management.membership_role_assignment IS
    'Deprecated upgrade ledger. Runtime authorization reads membership_role_definition only.';
