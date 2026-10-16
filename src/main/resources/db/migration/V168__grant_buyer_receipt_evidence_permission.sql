INSERT INTO tenant_management.permission_definition
    (permission_key, permission_group, display_name, description, reserved)
VALUES
    ('buyer.receipt.evidence.attach', 'DOCUMENTS', 'Attach buyer receipt evidence',
     'Attach scanned evidence to a disputed buyer receipt', TRUE)
ON CONFLICT (permission_key) DO NOTHING;

INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT role.id, permission.permission_key
FROM tenant_management.role_definition role
JOIN tenant_management.permission_definition permission
  ON permission.permission_key = 'buyer.receipt.evidence.attach'
WHERE role.tenant_id IS NULL AND role.workspace_id IS NULL
  AND role.code = 'buyer' AND role.status = 'ACTIVE'
ON CONFLICT (role_id, permission_key) DO NOTHING;
