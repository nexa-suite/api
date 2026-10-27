-- Grant the accepted catalog preparation capabilities to the fixed BOM role.
INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, p.permission_key
FROM tenant_management.role_definition r
JOIN tenant_management.permission_definition p
  ON p.permission_key IN ('catalog.read','catalog.product.manage','catalog.taxonomy.manage','catalog.price.manage')
WHERE r.tenant_id IS NULL AND r.workspace_id IS NULL
  AND r.code = 'business_operations_manager' AND r.status = 'ACTIVE'
ON CONFLICT (role_id, permission_key) DO NOTHING;
