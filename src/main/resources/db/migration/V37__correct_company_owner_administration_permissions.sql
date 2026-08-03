DELETE FROM tenant_management.role_permission
WHERE role_id = '7b5832ae-264f-38a1-8ee6-334bc641e42b'::uuid
  AND permission_key IN ('tenant.member.invite', 'tenant.role.assign');

INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT '71f7807b-0bdd-3aec-a83c-3a75f6e379b6'::uuid, permission_key
FROM unnest(ARRAY['catalog.promotion.read', 'catalog.promotion.manage', 'warehouse.read']) AS permission_key
ON CONFLICT (role_id, permission_key) DO NOTHING;
