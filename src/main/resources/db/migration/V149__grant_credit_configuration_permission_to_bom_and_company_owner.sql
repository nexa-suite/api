INSERT INTO tenant_management.permission_definition
    (permission_key, permission_group, display_name, description, reserved)
VALUES
    ('client.credit.configuration.manage', 'CLIENT_ACCOUNTS', 'Configure credit account',
     'Configure a customer credit limit and account active state; does not grant financial adjustments', TRUE)
ON CONFLICT (permission_key) DO NOTHING;

INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, p.permission_key
FROM tenant_management.role_definition r
JOIN tenant_management.permission_definition p
  ON p.permission_key = 'client.credit.configuration.manage'
WHERE r.tenant_id IS NULL
  AND r.code IN ('business_operations_manager', 'company_owner')
  AND r.status = 'ACTIVE'
ON CONFLICT (role_id, permission_key) DO NOTHING;
