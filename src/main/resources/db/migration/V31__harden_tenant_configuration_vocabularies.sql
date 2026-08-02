UPDATE tenant_management.reference_plan_assignment
SET plan_code = 'PROFESSIONAL'
WHERE plan_code = 'GROWTH';

ALTER TABLE tenant_management.reference_plan_assignment
    DROP CONSTRAINT ck_reference_plan_code;
ALTER TABLE tenant_management.reference_plan_assignment
    ADD CONSTRAINT ck_reference_plan_code
    CHECK (plan_code IN ('STARTER', 'STANDARD', 'PROFESSIONAL', 'ENTERPRISE'));

ALTER TABLE tenant_management.workspace_settings
    DROP CONSTRAINT ck_workspace_settings_warehouse_strategy;
ALTER TABLE tenant_management.workspace_settings
    DROP COLUMN warehouse_preference_strategy;

ALTER TABLE tenant_management.operational_settings
    RENAME COLUMN default_warehouse_selection_policy TO warehouse_preference_strategy;
ALTER TABLE tenant_management.operational_settings
    DROP CONSTRAINT ck_operational_settings_warehouse_policy;
ALTER TABLE tenant_management.operational_settings
    ADD CONSTRAINT ck_operational_settings_warehouse_strategy
    CHECK (warehouse_preference_strategy IN ('MANUAL', 'PREFERRED'));
