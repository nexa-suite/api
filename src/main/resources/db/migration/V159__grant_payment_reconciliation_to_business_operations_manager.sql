-- Wallet refunds use the existing reconciliation capability for the primary operator.
INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, p.permission_key
FROM tenant_management.role_definition r
JOIN tenant_management.permission_definition p
  ON p.permission_key = 'payment.reconcile'
WHERE r.tenant_id IS NULL
  AND r.code = 'business_operations_manager'
  AND r.status = 'ACTIVE'
ON CONFLICT (role_id, permission_key) DO NOTHING;
