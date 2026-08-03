-- V1-V34 are immutable. Operational settings is canonical for workspace
-- fulfillment, availability, hours and cutoff values from this version onward.
UPDATE tenant_management.operational_settings os
SET fulfillment_defaults = CASE
        WHEN os.fulfillment_defaults = 'STANDARD' AND ws.fulfillment_default <> 'STANDARD'
            THEN ws.fulfillment_default
        ELSE os.fulfillment_defaults
    END,
    inventory_visibility_policy = CASE
        WHEN os.inventory_visibility_policy = 'COARSE' AND ws.inventory_visibility_policy <> 'COARSE'
            THEN ws.inventory_visibility_policy
        ELSE os.inventory_visibility_policy
    END,
    buyer_availability_policy = CASE
        WHEN os.buyer_availability_policy = 'AVAILABLE_ONLY' AND ws.buyer_availability_policy <> 'AVAILABLE_ONLY'
            THEN ws.buyer_availability_policy
        ELSE os.buyer_availability_policy
    END,
    operating_hours_start = CASE
        WHEN os.operating_hours_start = TIME '08:00' AND ws.operating_hours_start <> TIME '08:00'
            THEN ws.operating_hours_start
        ELSE os.operating_hours_start
    END,
    operating_hours_end = CASE
        WHEN os.operating_hours_end = TIME '18:00' AND ws.operating_hours_end <> TIME '18:00'
            THEN ws.operating_hours_end
        ELSE os.operating_hours_end
    END,
    order_cutoff_minutes = CASE
        WHEN os.order_cutoff_minutes = 120 AND ws.order_cutoff_minutes <> 120
            THEN ws.order_cutoff_minutes
        ELSE os.order_cutoff_minutes
    END,
    updated_at = current_timestamp
FROM tenant_management.workspace_settings ws
WHERE ws.workspace_id = os.workspace_id;

ALTER TABLE tenant_management.workspace_settings
    DROP CONSTRAINT ck_workspace_settings_fulfillment,
    DROP CONSTRAINT ck_workspace_settings_inventory_visibility,
    DROP CONSTRAINT ck_workspace_settings_buyer_availability,
    DROP CONSTRAINT ck_workspace_settings_hours,
    DROP COLUMN fulfillment_default,
    DROP COLUMN inventory_visibility_policy,
    DROP COLUMN buyer_availability_policy,
    DROP COLUMN operating_hours_start,
    DROP COLUMN operating_hours_end,
    DROP COLUMN order_cutoff_minutes;

ALTER TABLE catalog_management.promotion
    ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;

ALTER TABLE catalog_management.promotion
    ADD CONSTRAINT ck_catalog_promotion_priority CHECK (priority BETWEEN -1000000 AND 1000000);

CREATE INDEX ix_catalog_promotion_priority
    ON catalog_management.promotion (tenant_id, workspace_id, status, priority DESC, starts_at, slug, id);

ALTER TABLE catalog_management.promotion_rule
    DROP CONSTRAINT ck_catalog_promotion_rule_type;

ALTER TABLE catalog_management.promotion_rule
    ADD CONSTRAINT ck_catalog_promotion_rule_type CHECK (
        rule_type IN ('MIN_ORDER_AMOUNT', 'CLIENT_ACCOUNT', 'CLIENT_ACCOUNT_ID', 'CLIENT_SEGMENT', 'BUYER_TIER', 'CURRENCY')
    );
