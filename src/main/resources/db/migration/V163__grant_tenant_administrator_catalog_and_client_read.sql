-- Keep persisted system-role grants aligned with their accepted query/read capabilities.
INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, requested.permission_key
FROM (VALUES
    ('tenant_admin', 'SYSTEM_RESERVED', 'catalog.read'),
    ('tenant_admin', 'SYSTEM_RESERVED', 'client.read')
) AS requested(role_code, role_type, permission_key)
JOIN tenant_management.role_definition r
  ON r.code = requested.role_code
 AND r.role_type = requested.role_type
 AND r.tenant_id IS NULL
 AND r.workspace_id IS NULL
 AND r.status = 'ACTIVE'
JOIN tenant_management.permission_definition p
  ON p.permission_key = requested.permission_key
ON CONFLICT (role_id, permission_key) DO NOTHING;
