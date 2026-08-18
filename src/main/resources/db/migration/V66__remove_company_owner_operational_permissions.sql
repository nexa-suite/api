-- Company Owner governs the tenant and commercial foundation; it is not an
-- automatic warehouse, fulfillment or logistics operator. Operational access
-- is granted only through a dedicated fixed role or an explicit custom role.
DELETE FROM tenant_management.role_permission
WHERE role_id = (
    SELECT id
    FROM tenant_management.role_definition
    WHERE tenant_id IS NULL
      AND workspace_id IS NULL
      AND code = 'company_owner'
)
AND permission_key IN (
    'warehouse.read', 'warehouse.location.manage',
    'inventory.read', 'inventory.receive', 'inventory.adjust',
    'inventory.reserve', 'inventory.release', 'inventory.waste',
    'fulfillment.read', 'fulfillment.manage',
    'logistics.read', 'dispatch.read', 'dispatch.assign',
    'dispatch.schedule', 'dispatch.start_route', 'dispatch.temperature',
    'dispatch.incident', 'dispatch.reprogram', 'dispatch.complete',
    'logistics.analytics.read'
);
