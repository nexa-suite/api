-- Company Owner may manage organization identity, workforce lifecycle and
-- allowed existing role assignments. Workspace administration, role
-- definition design and technical security remain Tenant Admin capabilities.
-- V1-V72 remain immutable.
INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, p.permission_key
FROM tenant_management.role_definition r
JOIN tenant_management.permission_definition p
  ON p.permission_key IN (
      'tenant.organization.manage',
      'tenant.member.invite',
      'tenant.member.manage',
      'tenant.role.assign'
  )
WHERE r.tenant_id IS NULL
  AND r.workspace_id IS NULL
  AND r.code = 'company_owner'
ON CONFLICT (role_id, permission_key) DO NOTHING;
