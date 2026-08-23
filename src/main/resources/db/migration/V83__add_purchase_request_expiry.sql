ALTER TABLE tenant_management.operational_settings
    ADD COLUMN purchase_request_expiry_days SMALLINT NOT NULL DEFAULT 3,
    ADD CONSTRAINT ck_operational_settings_purchase_request_expiry_days
        CHECK (purchase_request_expiry_days BETWEEN 1 AND 7);

ALTER TABLE sales.purchase_request
    ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE sales.purchase_request request
SET expires_at = request.created_at
    + make_interval(days => settings.purchase_request_expiry_days::INTEGER)
FROM tenant_management.operational_settings settings
WHERE settings.workspace_id = request.workspace_id
  AND request.expires_at IS NULL;

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_expires_at_after_creation
        CHECK (expires_at IS NULL OR expires_at >= created_at);

CREATE OR REPLACE FUNCTION sales.assign_purchase_request_expiry()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expiry_days integer;
BEGIN
    IF NEW.status <> 'DRAFT' AND NEW.expires_at IS NULL THEN
        SELECT purchase_request_expiry_days
          INTO expiry_days
          FROM tenant_management.operational_settings
         WHERE workspace_id = NEW.workspace_id;
        NEW.expires_at := coalesce(NEW.submitted_at, NEW.created_at, current_timestamp)
            + make_interval(days => coalesce(expiry_days, 3));
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER purchase_request_assign_expiry
    BEFORE INSERT OR UPDATE OF status, submitted_at, expires_at
    ON sales.purchase_request
    FOR EACH ROW
    EXECUTE FUNCTION sales.assign_purchase_request_expiry();

CREATE INDEX ix_purchase_request_expiry_due
    ON sales.purchase_request (tenant_id, workspace_id, status, expires_at, id)
    WHERE expires_at IS NOT NULL
      AND status IN ('SUBMITTED', 'IN_REVIEW', 'NEEDS_ADJUSTMENT', 'APPROVED');

DO $$
DECLARE
    target regclass;
    policy_name text;
BEGIN
    FOREACH target IN ARRAY ARRAY[
        'sales.purchase_request_event'::regclass,
        'sales.sales_order_event'::regclass,
        'sales.idempotency_record'::regclass
    ] LOOP
        policy_name := replace(target::text, '.', '_') || '_tenant_workspace_scope';
        EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
        EXECUTE format('DROP POLICY IF EXISTS %I ON %s', policy_name, target);
        EXECUTE format(
            'CREATE POLICY %I ON %s USING (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true)) WITH CHECK (tenant_id::text = current_setting(''app.current_tenant_id'', true) AND workspace_id::text = current_setting(''app.current_workspace_id'', true))',
            policy_name, target
        );
        EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    END LOOP;
END $$;
