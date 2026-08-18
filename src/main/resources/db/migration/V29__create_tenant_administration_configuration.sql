CREATE TABLE tenant_management.organization_settings (
    tenant_id UUID PRIMARY KEY,
    legal_name VARCHAR(160) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    business_identifier VARCHAR(80),
    operation_category VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_organization_settings_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT ck_organization_settings_names
        CHECK (length(btrim(legal_name)) BETWEEN 1 AND 160 AND length(btrim(display_name)) BETWEEN 1 AND 160)
);

CREATE TABLE tenant_management.workspace_settings (
    workspace_id UUID PRIMARY KEY,
    default_workspace_behavior VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    warehouse_preference_strategy VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    operating_hours_start TIME NOT NULL DEFAULT '08:00',
    operating_hours_end TIME NOT NULL DEFAULT '18:00',
    order_cutoff_minutes INTEGER NOT NULL DEFAULT 120,
    fulfillment_default VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    inventory_visibility_policy VARCHAR(32) NOT NULL DEFAULT 'COARSE',
    buyer_availability_policy VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE_ONLY',
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_workspace_settings_workspace
        FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace (id),
    CONSTRAINT ck_workspace_settings_behavior
        CHECK (default_workspace_behavior IN ('STANDARD', 'COMPACT')),
    CONSTRAINT ck_workspace_settings_warehouse_strategy
        CHECK (warehouse_preference_strategy IN ('MANUAL', 'PREFERRED')),
    CONSTRAINT ck_workspace_settings_cutoff
        CHECK (order_cutoff_minutes BETWEEN 0 AND 1440),
    CONSTRAINT ck_workspace_settings_fulfillment
        CHECK (fulfillment_default IN ('STANDARD', 'FEFO')),
    CONSTRAINT ck_workspace_settings_inventory_visibility
        CHECK (inventory_visibility_policy IN ('COARSE', 'DETAILED')),
    CONSTRAINT ck_workspace_settings_buyer_availability
        CHECK (buyer_availability_policy IN ('AVAILABLE_ONLY', 'ALL_ACTIVE')),
    CONSTRAINT ck_workspace_settings_hours
        CHECK (operating_hours_end > operating_hours_start)
);

CREATE TABLE tenant_management.regional_settings (
    tenant_id UUID PRIMARY KEY,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    language VARCHAR(8) NOT NULL DEFAULT 'en',
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    country_region VARCHAR(8) NOT NULL DEFAULT 'PE',
    date_time_policy VARCHAR(32) NOT NULL DEFAULT 'LOCALE',
    locale VARCHAR(16) NOT NULL DEFAULT 'en-US',
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_regional_settings_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT ck_regional_settings_language CHECK (language IN ('en', 'es')),
    CONSTRAINT ck_regional_settings_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_regional_settings_date_policy CHECK (date_time_policy IN ('LOCALE', 'ISO_8601', 'US'))
);

CREATE TABLE tenant_management.unit_preferences (
    tenant_id UUID PRIMARY KEY,
    mass_unit VARCHAR(16) NOT NULL DEFAULT 'KG',
    temperature_unit VARCHAR(16) NOT NULL DEFAULT 'CELSIUS',
    distance_unit VARCHAR(16) NOT NULL DEFAULT 'KM',
    volume_unit VARCHAR(16) NOT NULL DEFAULT 'M3',
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_unit_preferences_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT ck_unit_preferences_mass CHECK (mass_unit IN ('KG', 'LB')),
    CONSTRAINT ck_unit_preferences_temperature CHECK (temperature_unit IN ('CELSIUS', 'FAHRENHEIT')),
    CONSTRAINT ck_unit_preferences_distance CHECK (distance_unit IN ('KM', 'MI')),
    CONSTRAINT ck_unit_preferences_volume CHECK (volume_unit IN ('M3', 'PALLET', 'FT3'))
);

CREATE TABLE tenant_management.operational_settings (
    workspace_id UUID PRIMARY KEY,
    default_warehouse_selection_policy VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    order_cutoff_policy VARCHAR(32) NOT NULL DEFAULT 'WORKSPACE_HOURS',
    fulfillment_defaults VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    inventory_visibility_policy VARCHAR(32) NOT NULL DEFAULT 'COARSE',
    buyer_availability_policy VARCHAR(32) NOT NULL DEFAULT 'AVAILABLE_ONLY',
    operating_hours_start TIME NOT NULL DEFAULT '08:00',
    operating_hours_end TIME NOT NULL DEFAULT '18:00',
    order_cutoff_minutes INTEGER NOT NULL DEFAULT 120,
    thermal_log_required BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_operational_settings_workspace
        FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace (id),
    CONSTRAINT ck_operational_settings_warehouse_policy
        CHECK (default_warehouse_selection_policy IN ('MANUAL', 'PREFERRED')),
    CONSTRAINT ck_operational_settings_cutoff_policy
        CHECK (order_cutoff_policy IN ('WORKSPACE_HOURS', 'FIXED_TIME')),
    CONSTRAINT ck_operational_settings_fulfillment
        CHECK (fulfillment_defaults IN ('STANDARD', 'FEFO')),
    CONSTRAINT ck_operational_settings_inventory
        CHECK (inventory_visibility_policy IN ('COARSE', 'DETAILED')),
    CONSTRAINT ck_operational_settings_buyer
        CHECK (buyer_availability_policy IN ('AVAILABLE_ONLY', 'ALL_ACTIVE')),
    CONSTRAINT ck_operational_settings_cutoff
        CHECK (order_cutoff_minutes BETWEEN 0 AND 1440),
    CONSTRAINT ck_operational_settings_hours
        CHECK (operating_hours_end > operating_hours_start)
);

CREATE TABLE tenant_management.notification_preference (
    workspace_id UUID NOT NULL,
    event_category VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, event_category, channel),
    CONSTRAINT fk_notification_preference_workspace
        FOREIGN KEY (workspace_id) REFERENCES tenant_management.workspace (id),
    CONSTRAINT ck_notification_preference_channel CHECK (channel IN ('IN_APP', 'EMAIL')),
    CONSTRAINT ck_notification_preference_category CHECK (event_category IN ('TEMPERATURE_ALERT', 'DOCUMENT_REMINDER', 'ORDER_STATUS', 'INVITATION'))
);
CREATE INDEX ix_notification_preference_workspace_category
    ON tenant_management.notification_preference (workspace_id, event_category);

CREATE TABLE tenant_management.tenant_security_settings (
    tenant_id UUID PRIMARY KEY,
    password_min_length INTEGER NOT NULL DEFAULT 12,
    session_duration_minutes INTEGER NOT NULL DEFAULT 480,
    invitation_expiration_hours INTEGER NOT NULL DEFAULT 72,
    required_email_domain VARCHAR(254),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_tenant_security_settings_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT ck_tenant_security_password CHECK (password_min_length BETWEEN 12 AND 128),
    CONSTRAINT ck_tenant_security_session CHECK (session_duration_minutes BETWEEN 30 AND 1440),
    CONSTRAINT ck_tenant_security_invitation CHECK (invitation_expiration_hours BETWEEN 1 AND 168),
    CONSTRAINT ck_tenant_security_domain CHECK (required_email_domain IS NULL OR required_email_domain ~ '^[A-Za-z0-9.-]+$')
);

CREATE TABLE tenant_management.custom_field_definition (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    field_key VARCHAR(64) NOT NULL,
    label VARCHAR(160) NOT NULL,
    field_kind VARCHAR(16) NOT NULL,
    scope VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    unique_value BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_custom_field_definition_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT fk_custom_field_definition_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT uq_custom_field_definition_key UNIQUE (workspace_id, field_key),
    CONSTRAINT ck_custom_field_definition_kind CHECK (field_kind IN ('TEXT', 'NUMBER', 'DECIMAL', 'BOOLEAN', 'DATE')),
    CONSTRAINT ck_custom_field_definition_scope CHECK (scope IN ('PRODUCT', 'CLIENT_ACCOUNT', 'DISPATCH', 'ORDER')),
    CONSTRAINT ck_custom_field_definition_order CHECK (display_order BETWEEN 0 AND 10000)
);
CREATE INDEX ix_custom_field_definition_scope
    ON tenant_management.custom_field_definition (workspace_id, scope, active, display_order);

CREATE TABLE tenant_management.reference_plan_assignment (
    tenant_id UUID PRIMARY KEY,
    plan_code VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    monthly_price NUMERIC(12, 2) NOT NULL DEFAULT 0,
    seat_limit INTEGER NOT NULL DEFAULT 10,
    workspace_limit INTEGER NOT NULL DEFAULT 3,
    transaction_limit INTEGER NOT NULL DEFAULT 1000,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_reference_plan_assignment_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT ck_reference_plan_code CHECK (plan_code IN ('STARTER', 'STANDARD', 'GROWTH')),
    CONSTRAINT ck_reference_plan_limits CHECK (monthly_price >= 0 AND seat_limit > 0 AND workspace_limit > 0 AND transaction_limit > 0)
);

CREATE TABLE tenant_management.organization_invitation (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    email VARCHAR(254) NOT NULL,
    normalized_email VARCHAR(254) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by_membership_id UUID NOT NULL,
    accepted_user_id UUID,
    accepted_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_organization_invitation_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT fk_organization_invitation_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT fk_organization_invitation_creator
        FOREIGN KEY (workspace_id, created_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_organization_invitation_user
        FOREIGN KEY (accepted_user_id) REFERENCES iam.user_account (id),
    CONSTRAINT ck_organization_invitation_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED'))
);
CREATE UNIQUE INDEX uq_organization_invitation_active_email
    ON tenant_management.organization_invitation (workspace_id, normalized_email)
    WHERE status = 'PENDING';
CREATE INDEX ix_organization_invitation_scope
    ON tenant_management.organization_invitation (tenant_id, workspace_id, status, created_at DESC);
CREATE INDEX ix_organization_invitation_token
    ON tenant_management.organization_invitation (token_hash, status, expires_at);

CREATE TABLE tenant_management.organization_invitation_role (
    invitation_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (invitation_id, role),
    CONSTRAINT fk_organization_invitation_role_invitation
        FOREIGN KEY (invitation_id) REFERENCES tenant_management.organization_invitation (id),
    CONSTRAINT ck_organization_invitation_role CHECK (role IN ('TENANT_ADMIN', 'COMPANY_OWNER', 'SALES', 'WAREHOUSE', 'LOGISTICS'))
);

CREATE TABLE tenant_management.organization_invitation_idempotency (
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    invitation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT fk_invitation_idempotency_invitation
        FOREIGN KEY (invitation_id) REFERENCES tenant_management.organization_invitation (id),
    CONSTRAINT uq_invitation_idempotency_invitation UNIQUE (invitation_id)
);

CREATE TABLE tenant_management.workspace_creation_idempotency (
    tenant_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    workspace_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT fk_workspace_creation_idempotency_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenant_management.tenant (id),
    CONSTRAINT fk_workspace_creation_idempotency_workspace
        FOREIGN KEY (tenant_id, workspace_id) REFERENCES tenant_management.workspace (tenant_id, id),
    CONSTRAINT uq_workspace_creation_idempotency_workspace UNIQUE (workspace_id)
);
