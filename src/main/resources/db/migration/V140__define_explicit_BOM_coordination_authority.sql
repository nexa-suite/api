-- Coordination is separate from underlying goods disposition authority.
INSERT INTO tenant_management.permission_definition
 (permission_key,permission_group,display_name,description,reserved)
VALUES
 ('delivery.exception.read','LOGISTICS','Read operational exceptions','Read current scoped cross-functional exception cases',TRUE),
 ('delivery.exception.coordinate','LOGISTICS','Coordinate operational exceptions','Assign, follow up and close exceptions after valid underlying resolution',TRUE),
 ('delivery.execution_hold.dispose','LOGISTICS','Dispose execution HOLD','Record explicitly authorized in-transit cold-chain dispositions',TRUE)
ON CONFLICT(permission_key) DO NOTHING;

INSERT INTO tenant_management.role_definition
 (id,tenant_id,workspace_id,code,name,description,role_type,status,created_by_membership_id,created_at,updated_at,version)
VALUES
 ('007b12ab-81dd-307a-ac41-9bc9888924ed',NULL,NULL,'business_operations_manager','Business operations manager',
 'Coordinates operational exceptions without implicit stock or cold-chain authority','SYSTEM_TEMPLATE','ACTIVE',NULL,current_timestamp,current_timestamp,0)
ON CONFLICT(id) DO NOTHING;

INSERT INTO tenant_management.role_permission(role_id,permission_key)
SELECT r.id,p.permission_key
FROM tenant_management.role_definition r
JOIN tenant_management.permission_definition p
 ON p.permission_key IN('delivery.exception.read','delivery.exception.coordinate','notification.read','notification.manage_preferences')
WHERE r.tenant_id IS NULL AND r.code='business_operations_manager'
ON CONFLICT(role_id,permission_key) DO NOTHING;

-- No memberships are assigned automatically. No built-in role receives execution
-- disposition authority through this migration; explicit role management grants it.
