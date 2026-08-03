CREATE SCHEMA IF NOT EXISTS reference_data;
CREATE SCHEMA IF NOT EXISTS notifications;
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS tenant_management.permission_definition (
    permission_key VARCHAR(120) PRIMARY KEY,
    permission_group VARCHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description VARCHAR(500) NOT NULL,
    reserved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    CONSTRAINT ck_permission_definition_key CHECK (permission_key ~ '^[a-z][a-z0-9_.-]+$')
);

INSERT INTO tenant_management.permission_definition (permission_key, permission_group, display_name, description, reserved)
VALUES
    ('tenant.organization.read', 'TENANT_ADMINISTRATION', 'Read organization', 'Read organization settings and status', TRUE),
    ('tenant.organization.manage', 'TENANT_ADMINISTRATION', 'Manage organization', 'Manage organization technical settings', TRUE),
    ('tenant.workspace.read', 'TENANT_ADMINISTRATION', 'Read workspaces', 'Read tenant workspaces', TRUE),
    ('tenant.workspace.manage', 'TENANT_ADMINISTRATION', 'Manage workspaces', 'Create and manage workspaces', TRUE),
    ('tenant.member.read', 'MEMBERS_AND_ROLES', 'Read members', 'Read workspace members', TRUE),
    ('tenant.member.invite', 'MEMBERS_AND_ROLES', 'Invite members', 'Invite workspace members', TRUE),
    ('tenant.member.manage', 'MEMBERS_AND_ROLES', 'Manage members', 'Manage workspace members', TRUE),
    ('tenant.role.read', 'MEMBERS_AND_ROLES', 'Read roles', 'Read role definitions and permissions', TRUE),
    ('tenant.role.manage', 'MEMBERS_AND_ROLES', 'Manage roles', 'Create, edit and deactivate custom roles', TRUE),
    ('tenant.role.assign', 'MEMBERS_AND_ROLES', 'Assign roles', 'Assign allowed roles to members', TRUE),
    ('tenant.role.assign_reserved', 'MEMBERS_AND_ROLES', 'Assign reserved roles', 'Assign reserved system roles', TRUE),
    ('tenant.security.manage', 'SECURITY', 'Manage security', 'Manage technical security settings', TRUE),
    ('tenant.audit.read', 'AUDIT', 'Read audit', 'Read safe technical audit events', TRUE),
    ('catalog.read', 'CATALOG', 'Read catalog', 'Read products and commercial catalog data', TRUE),
    ('catalog.product.manage', 'CATALOG', 'Manage products', 'Create and manage products', TRUE),
    ('catalog.taxonomy.manage', 'CATALOG', 'Manage taxonomy', 'Manage categories and brands', TRUE),
    ('catalog.price.manage', 'CATALOG', 'Manage prices', 'Create and manage prices', TRUE),
    ('catalog.promotion.read', 'CATALOG', 'Read promotions', 'Read promotions', TRUE),
    ('catalog.promotion.manage', 'CATALOG', 'Manage promotions', 'Create and manage promotions', TRUE),
    ('sales.dashboard.read', 'SALES', 'Read sales dashboard', 'Read commercial dashboard', TRUE),
    ('sales.purchase_request.read', 'SALES', 'Read purchase requests', 'Read purchase requests', TRUE),
    ('sales.purchase_request.review', 'SALES', 'Review purchase requests', 'Review and approve purchase requests', TRUE),
    ('sales.order.read', 'SALES', 'Read sales orders', 'Read sales orders', TRUE),
    ('sales.order.create_manual', 'SALES', 'Create manual sales orders', 'Create direct sales orders', TRUE),
    ('sales.order.manage', 'SALES', 'Manage sales orders', 'Manage sales order lifecycle', TRUE),
    ('client.read', 'CLIENT_ACCOUNTS', 'Read client accounts', 'Read client account profiles', TRUE),
    ('client.manage', 'CLIENT_ACCOUNTS', 'Manage client accounts', 'Manage client account profiles', TRUE),
    ('client.address.manage', 'CLIENT_ACCOUNTS', 'Manage client addresses', 'Manage authorized client addresses', TRUE),
    ('client.commercial_terms.manage', 'CLIENT_ACCOUNTS', 'Manage commercial terms', 'Manage payment and credit terms', TRUE),
    ('warehouse.read', 'WAREHOUSE', 'Read warehouses', 'Read warehouses and locations', TRUE),
    ('warehouse.location.manage', 'WAREHOUSE', 'Manage warehouse locations', 'Manage business warehouse configuration', TRUE),
    ('inventory.read', 'INVENTORY', 'Read inventory', 'Read inventory and lots', TRUE),
    ('inventory.receive', 'INVENTORY', 'Receive inventory', 'Receive inventory', TRUE),
    ('inventory.adjust', 'INVENTORY', 'Adjust inventory', 'Adjust inventory', TRUE),
    ('inventory.reserve', 'INVENTORY', 'Reserve inventory', 'Reserve inventory', TRUE),
    ('inventory.release', 'INVENTORY', 'Release inventory', 'Release inventory', TRUE),
    ('inventory.waste', 'INVENTORY', 'Record waste', 'Record waste and quarantine', TRUE),
    ('fulfillment.read', 'FULFILLMENT', 'Read fulfillment', 'Read fulfillment readiness', TRUE),
    ('fulfillment.manage', 'FULFILLMENT', 'Manage fulfillment', 'Manage fulfillment readiness', TRUE),
    ('logistics.read', 'LOGISTICS', 'Read logistics', 'Read delivery operations', TRUE),
    ('dispatch.read', 'LOGISTICS', 'Read dispatch', 'Read dispatch board', TRUE),
    ('dispatch.assign', 'LOGISTICS', 'Assign dispatch', 'Assign dispatch operator and vehicle', TRUE),
    ('dispatch.schedule', 'LOGISTICS', 'Schedule dispatch', 'Schedule dispatch', TRUE),
    ('dispatch.start_route', 'LOGISTICS', 'Start route', 'Start delivery route', TRUE),
    ('dispatch.temperature', 'LOGISTICS', 'Record temperature', 'Record temperature readings', TRUE),
    ('dispatch.incident', 'LOGISTICS', 'Record incident', 'Record delivery incidents', TRUE),
    ('dispatch.reprogram', 'LOGISTICS', 'Reprogram dispatch', 'Reprogram delivery', TRUE),
    ('dispatch.complete', 'LOGISTICS', 'Complete delivery', 'Complete delivery with POD metadata', TRUE),
    ('logistics.analytics.read', 'ANALYTICS', 'Read logistics analytics', 'Read logistics metrics', TRUE),
    ('notification.read', 'NOTIFICATIONS', 'Read notifications', 'Read scoped notifications', TRUE),
    ('notification.manage_preferences', 'NOTIFICATIONS', 'Manage notification preferences', 'Manage notification preferences', TRUE),
    ('order_export.read', 'ORDER_EXPORTS', 'Export order summaries', 'Download order summary PDF and CSV', TRUE),
    ('analytics.executive.read', 'ANALYTICS', 'Read executive analytics', 'Read executive analytics', TRUE),
    ('buyer.sales.read', 'SALES', 'Read buyer sales', 'Read buyer catalog and request data', TRUE),
    ('buyer.sales.write', 'SALES', 'Write buyer sales', 'Build and submit buyer requests', TRUE),
    ('buyer.order.read', 'SALES', 'Read buyer orders', 'Read buyer orders', TRUE),
    ('buyer.tracking.read', 'LOGISTICS', 'Read buyer tracking', 'Read buyer delivery tracking', TRUE),
    ('buyer.profile.write', 'MEMBERS_AND_ROLES', 'Manage buyer profile', 'Manage buyer profile and delivery preferences', TRUE)
ON CONFLICT (permission_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS tenant_management.role_definition (
    id UUID PRIMARY KEY,
    tenant_id UUID,
    workspace_id UUID,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(500) NOT NULL,
    role_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_by_membership_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_role_definition_tenant FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT fk_role_definition_workspace FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_role_definition_type CHECK (role_type IN ('SYSTEM_RESERVED','SYSTEM_TEMPLATE','CUSTOM')),
    CONSTRAINT ck_role_definition_status CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT ck_role_definition_code CHECK (code ~ '^[a-z][a-z0-9_.-]{1,63}$')
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_role_definition_scope_code
    ON tenant_management.role_definition (tenant_id, COALESCE(workspace_id, '00000000-0000-0000-0000-000000000000'::uuid), code);
CREATE INDEX IF NOT EXISTS ix_role_definition_scope_status
    ON tenant_management.role_definition (tenant_id, workspace_id, status, role_type, code);

CREATE TABLE IF NOT EXISTS tenant_management.role_permission (
    role_id UUID NOT NULL,
    permission_key VARCHAR(120) NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (role_id, permission_key),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES tenant_management.role_definition (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_definition FOREIGN KEY (permission_key) REFERENCES tenant_management.permission_definition (permission_key)
);

INSERT INTO tenant_management.role_definition
    (id, tenant_id, workspace_id, code, name, description, role_type, status, created_by_membership_id, created_at, updated_at, version)
VALUES
    ('d43801d8-166a-3db1-bf60-d88a4fd14798', NULL, NULL, 'tenant_admin', 'Tenant administrator', 'Nexa reserved tenant administration role', 'SYSTEM_RESERVED', 'ACTIVE', NULL, current_timestamp, current_timestamp, 0),
    ('7b5832ae-264f-38a1-8ee6-334bc641e42b', NULL, NULL, 'company_owner', 'Company owner', 'Nexa reserved company owner role', 'SYSTEM_RESERVED', 'ACTIVE', NULL, current_timestamp, current_timestamp, 0),
    ('6c748ce2-e99d-3db5-a949-72b508693b3a', NULL, NULL, 'sales', 'Sales', 'Nexa commercial sales role', 'SYSTEM_TEMPLATE', 'ACTIVE', NULL, current_timestamp, current_timestamp, 0),
    ('5d4b5683-524d-3cb1-8e14-69b7451ccc0f', NULL, NULL, 'warehouse', 'Warehouse', 'Nexa warehouse operations role', 'SYSTEM_TEMPLATE', 'ACTIVE', NULL, current_timestamp, current_timestamp, 0),
    ('71f7807b-0bdd-3aec-a83c-3a75f6e379b6', NULL, NULL, 'logistics', 'Logistics', 'Nexa logistics operations role', 'SYSTEM_TEMPLATE', 'ACTIVE', NULL, current_timestamp, current_timestamp, 0),
    ('361a77f8-77a4-3445-b70f-73c64d4563f3', NULL, NULL, 'buyer', 'Buyer', 'Nexa buyer portal role', 'SYSTEM_TEMPLATE', 'ACTIVE', NULL, current_timestamp, current_timestamp, 0)
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant_management.role_permission (role_id, permission_key)
SELECT r.id, permission_key
FROM tenant_management.role_definition r
JOIN LATERAL unnest(string_to_array(CASE r.code
    WHEN 'tenant_admin' THEN 'tenant.organization.read,tenant.organization.manage,tenant.workspace.read,tenant.workspace.manage,tenant.member.read,tenant.member.invite,tenant.member.manage,tenant.role.read,tenant.role.manage,tenant.role.assign,tenant.role.assign_reserved,tenant.security.manage,tenant.audit.read,notification.read,notification.manage_preferences'
    WHEN 'company_owner' THEN 'tenant.organization.read,tenant.workspace.read,tenant.member.read,tenant.member.invite,tenant.role.read,tenant.role.assign,catalog.read,catalog.product.manage,catalog.taxonomy.manage,catalog.price.manage,catalog.promotion.read,catalog.promotion.manage,sales.dashboard.read,sales.purchase_request.read,sales.order.read,client.read,client.manage,client.address.manage,client.commercial_terms.manage,warehouse.read,warehouse.location.manage,logistics.read,analytics.executive.read,order_export.read,notification.read'
    WHEN 'sales' THEN 'catalog.read,catalog.product.manage,catalog.taxonomy.manage,catalog.price.manage,catalog.promotion.read,sales.dashboard.read,sales.purchase_request.read,sales.purchase_request.review,sales.order.read,sales.order.create_manual,sales.order.manage,client.read,client.manage,client.address.manage,client.commercial_terms.manage,order_export.read,notification.read'
    WHEN 'warehouse' THEN 'catalog.read,warehouse.read,inventory.read,inventory.receive,inventory.adjust,inventory.reserve,inventory.release,inventory.waste,fulfillment.read,fulfillment.manage,notification.read'
    WHEN 'logistics' THEN 'catalog.read,fulfillment.read,logistics.read,dispatch.read,dispatch.assign,dispatch.schedule,dispatch.start_route,dispatch.temperature,dispatch.incident,dispatch.reprogram,dispatch.complete,logistics.analytics.read,notification.read'
    WHEN 'buyer' THEN 'catalog.read,catalog.promotion.read,buyer.sales.read,buyer.sales.write,buyer.order.read,buyer.tracking.read,notification.read,buyer.profile.write'
END, ',')) AS permissions(permission_key) ON TRUE
WHERE r.tenant_id IS NULL
ON CONFLICT (role_id, permission_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS tenant_management.membership_role_definition (
    membership_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    role_id UUID NOT NULL,
    assigned_by_membership_id UUID,
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (membership_id, role_id),
    CONSTRAINT fk_membership_role_definition_membership FOREIGN KEY (workspace_id, membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_role_definition_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_membership_role_definition_role FOREIGN KEY (role_id) REFERENCES tenant_management.role_definition (id)
);
CREATE INDEX IF NOT EXISTS ix_membership_role_definition_scope
    ON tenant_management.membership_role_definition (tenant_id, workspace_id, membership_id);

CREATE TABLE IF NOT EXISTS tenant_management.membership_authorization_state (
    membership_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    authorization_version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_membership_authorization_state_membership FOREIGN KEY (workspace_id, membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_authorization_state_scope FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_membership_authorization_state_version CHECK (authorization_version >= 0)
);

CREATE TABLE IF NOT EXISTS reference_data.department (
    code VARCHAR(16) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    source VARCHAR(160) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
CREATE TABLE IF NOT EXISTS reference_data.province (
    code VARCHAR(16) PRIMARY KEY,
    department_code VARCHAR(16) NOT NULL REFERENCES reference_data.department(code),
    name VARCHAR(120) NOT NULL,
    source VARCHAR(160) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
CREATE TABLE IF NOT EXISTS reference_data.district (
    code VARCHAR(16) PRIMARY KEY,
    department_code VARCHAR(16) NOT NULL REFERENCES reference_data.department(code),
    province_code VARCHAR(16) NOT NULL REFERENCES reference_data.province(code),
    name VARCHAR(120) NOT NULL,
    source VARCHAR(160) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
CREATE TABLE IF NOT EXISTS reference_data.road_type (
    code VARCHAR(32) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    loaded_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);
INSERT INTO reference_data.department(code, name, source, dataset_version) VALUES ('15', 'Lima', 'INEI UBIGEO reference', '2C-local-1') ON CONFLICT DO NOTHING;
INSERT INTO reference_data.province(code, department_code, name, source, dataset_version) VALUES ('1501', '15', 'Lima', 'INEI UBIGEO reference', '2C-local-1') ON CONFLICT DO NOTHING;
INSERT INTO reference_data.district(code, department_code, province_code, name, source, dataset_version) VALUES ('150101', '15', '1501', 'Lima', 'INEI UBIGEO reference', '2C-local-1') ON CONFLICT DO NOTHING;
INSERT INTO reference_data.road_type(code, name, dataset_version) VALUES ('STREET', 'Calle', '2C-local-1'), ('AVENUE', 'Avenida', '2C-local-1'), ('JIRON', 'Jirón', '2C-local-1'), ('ROAD', 'Carretera', '2C-local-1') ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS sales.client_account_address (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    client_account_id UUID NOT NULL,
    label VARCHAR(120) NOT NULL,
    recipient_name VARCHAR(160) NOT NULL,
    recipient_phone VARCHAR(48),
    road_type VARCHAR(32),
    street_name VARCHAR(180),
    street_number VARCHAR(32),
    interior VARCHAR(64),
    address_line VARCHAR(500) NOT NULL,
    department_code VARCHAR(16),
    province_code VARCHAR(16),
    district_code VARCHAR(16),
    postal_code VARCHAR(32),
    reference VARCHAR(500),
    receiving_instructions VARCHAR(1000),
    receiving_hours VARCHAR(240),
    latitude NUMERIC(10,7),
    longitude NUMERIC(10,7),
    place_id VARCHAR(240),
    source VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    default_address BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_client_address_account FOREIGN KEY (tenant_id, workspace_id, client_account_id)
        REFERENCES sales.client_account (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_client_address_source CHECK (source IN ('MANUAL','SAVED','CURRENT_LOCATION','MAP_PIN','PLACES')),
    CONSTRAINT ck_client_address_status CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT ck_client_address_coordinates CHECK ((latitude IS NULL AND longitude IS NULL) OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);
CREATE INDEX IF NOT EXISTS ix_client_address_scope_account ON sales.client_account_address (tenant_id, workspace_id, client_account_id, status, updated_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_client_address_default_active ON sales.client_account_address (client_account_id) WHERE default_address AND status = 'ACTIVE';

ALTER TABLE sales.client_account ADD COLUMN IF NOT EXISTS credit_limit NUMERIC(18,4) NOT NULL DEFAULT 0;
ALTER TABLE sales.client_account ADD COLUMN IF NOT EXISTS current_commercial_exposure NUMERIC(18,4) NOT NULL DEFAULT 0;
ALTER TABLE sales.client_account ADD COLUMN IF NOT EXISTS available_credit NUMERIC(18,4) NOT NULL DEFAULT 0;
ALTER TABLE sales.client_account ADD COLUMN IF NOT EXISTS default_payment_preference VARCHAR(80);
ALTER TABLE sales.client_account ADD COLUMN IF NOT EXISTS sales_owner_membership_id UUID;

ALTER TABLE sales.purchase_request ADD COLUMN IF NOT EXISTS delivery_address_snapshot JSONB;
ALTER TABLE sales.purchase_request ADD COLUMN IF NOT EXISTS route_snapshot JSONB;
ALTER TABLE sales.purchase_request ADD COLUMN IF NOT EXISTS warehouse_selection_snapshot JSONB;
ALTER TABLE sales.purchase_request ADD COLUMN IF NOT EXISTS commercial_snapshot JSONB;
ALTER TABLE sales.sales_order ADD COLUMN IF NOT EXISTS order_source VARCHAR(32) NOT NULL DEFAULT 'PURCHASE_REQUEST';
ALTER TABLE sales.sales_order ALTER COLUMN source_purchase_request_id DROP NOT NULL;
ALTER TABLE sales.sales_order ADD COLUMN IF NOT EXISTS delivery_address_snapshot JSONB;
ALTER TABLE sales.sales_order ADD COLUMN IF NOT EXISTS route_snapshot JSONB;
ALTER TABLE sales.sales_order ADD COLUMN IF NOT EXISTS warehouse_selection_snapshot JSONB;
ALTER TABLE sales.sales_order ADD COLUMN IF NOT EXISTS commercial_snapshot JSONB;
ALTER TABLE sales.sales_order DROP CONSTRAINT IF EXISTS fk_sales_order_source_request;
ALTER TABLE sales.sales_order ADD CONSTRAINT fk_sales_order_source_request FOREIGN KEY (tenant_id, workspace_id, source_purchase_request_id)
    REFERENCES sales.purchase_request (tenant_id, workspace_id, id);
ALTER TABLE sales.sales_order ADD CONSTRAINT ck_sales_order_source CHECK (order_source IN ('PURCHASE_REQUEST','MANUAL'));
CREATE TABLE IF NOT EXISTS sales.manual_order_idempotency (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    sales_order_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, workspace_id, actor_membership_id, idempotency_key),
    CONSTRAINT fk_manual_order_idempotency_order FOREIGN KEY (tenant_id, workspace_id, sales_order_id)
        REFERENCES sales.sales_order (tenant_id, workspace_id, id)
);

CREATE TABLE IF NOT EXISTS warehouse.warehouse_service_configuration (
    warehouse_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    service_status VARCHAR(16) NOT NULL DEFAULT 'OPERATIONAL',
    service_area_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    priority INTEGER NOT NULL DEFAULT 0,
    preferred BOOLEAN NOT NULL DEFAULT FALSE,
    operating_hours_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    supported_fulfillment_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_warehouse_service_configuration_warehouse FOREIGN KEY (tenant_id, workspace_id, warehouse_id)
        REFERENCES warehouse.warehouse (tenant_id, workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_warehouse_service_configuration_status CHECK (service_status IN ('OPERATIONAL','CLOSED','BLOCKED'))
);
CREATE TABLE IF NOT EXISTS warehouse.selection_snapshot (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    purchase_request_id UUID,
    sales_order_id UUID,
    warehouse_id UUID NOT NULL,
    explanation JSONB NOT NULL,
    origin_latitude NUMERIC(10,7),
    origin_longitude NUMERIC(10,7),
    destination_latitude NUMERIC(10,7),
    destination_longitude NUMERIC(10,7),
    distance_km NUMERIC(12,3),
    duration_seconds BIGINT,
    calculated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_selection_snapshot_scope FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_selection_snapshot_target CHECK ((purchase_request_id IS NOT NULL) OR (sales_order_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS ix_selection_snapshot_scope_target ON warehouse.selection_snapshot (tenant_id, workspace_id, purchase_request_id, sales_order_id);

CREATE TABLE IF NOT EXISTS logistics.operational_handoff_note (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    sales_order_id UUID,
    dispatch_order_id UUID,
    author_membership_id UUID NOT NULL,
    author_work_area VARCHAR(24) NOT NULL,
    category VARCHAR(24) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    visibility VARCHAR(24) NOT NULL DEFAULT 'WAREHOUSE_LOGISTICS',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_handoff_scope FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT ck_handoff_author_area CHECK (author_work_area IN ('WAREHOUSE','LOGISTICS','SALES','TENANT_ADMIN','COMPANY_OWNER')),
    CONSTRAINT ck_handoff_category CHECK (category IN ('READINESS','SHORTAGE','TEMPERATURE','PACKING','DISPATCH','INCIDENT','GENERAL')),
    CONSTRAINT ck_handoff_target CHECK (sales_order_id IS NOT NULL OR dispatch_order_id IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS ix_handoff_scope_order ON logistics.operational_handoff_note (tenant_id, workspace_id, sales_order_id, created_at, id);
CREATE INDEX IF NOT EXISTS ix_handoff_scope_dispatch ON logistics.operational_handoff_note (tenant_id, workspace_id, dispatch_order_id, created_at, id);
CREATE TRIGGER logistics_handoff_append_only
    BEFORE UPDATE OR DELETE ON logistics.operational_handoff_note FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE IF NOT EXISTS notifications.inbox_item (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    recipient_membership_id UUID NOT NULL,
    event_id UUID NOT NULL,
    category VARCHAR(64) NOT NULL,
    title VARCHAR(240) NOT NULL,
    message VARCHAR(2000) NOT NULL,
    deep_link VARCHAR(500),
    subject_type VARCHAR(64),
    subject_id UUID,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_notification_scope FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_inbox_event_recipient
    ON notifications.inbox_item (event_id, recipient_membership_id);
CREATE INDEX IF NOT EXISTS ix_notification_inbox_recipient ON notifications.inbox_item (tenant_id, workspace_id, recipient_membership_id, read_at, created_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_notification_inbox_subject ON notifications.inbox_item (tenant_id, workspace_id, subject_type, subject_id, created_at DESC);

CREATE TABLE IF NOT EXISTS audit.event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    actor_membership_id UUID,
    actor_work_area VARCHAR(32),
    event_type VARCHAR(120) NOT NULL,
    subject_type VARCHAR(120),
    subject_id UUID,
    correlation_id VARCHAR(120),
    safe_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_audit_scope FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id)
);
CREATE INDEX IF NOT EXISTS ix_audit_scope_time ON audit.event (tenant_id, workspace_id, occurred_at DESC, id);
CREATE INDEX IF NOT EXISTS ix_audit_scope_type ON audit.event (tenant_id, workspace_id, event_type, occurred_at DESC, id);
CREATE OR REPLACE FUNCTION audit.prevent_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Audit event is append-only';
END;
$$;
CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit.event FOR EACH ROW EXECUTE FUNCTION audit.prevent_append_only_mutation();
