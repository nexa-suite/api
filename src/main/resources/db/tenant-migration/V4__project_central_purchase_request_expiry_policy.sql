CREATE TABLE nexa_platform.purchase_request_expiry_policy_snapshot (
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    source_state VARCHAR(24) NOT NULL,
    source_version BIGINT,
    expiry_days SMALLINT NOT NULL,
    snapshot_revision BIGINT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_purchase_request_expiry_policy_snapshot
        PRIMARY KEY (tenant_id, workspace_id),
    CONSTRAINT fk_purchase_request_expiry_policy_snapshot_scope
        FOREIGN KEY (tenant_id, workspace_id)
        REFERENCES nexa_platform.tenant_workspace_scope_anchor (tenant_id, workspace_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_purchase_request_expiry_policy_snapshot_source
        CHECK (
            (source_state = 'PRESENT' AND source_version >= 0 AND expiry_days BETWEEN 1 AND 7)
            OR (source_state = 'CONFIRMED_ABSENT' AND source_version IS NULL AND expiry_days = 3)
        ),
    CONSTRAINT ck_purchase_request_expiry_policy_snapshot_revision
        CHECK (snapshot_revision > 0)
);

COMMENT ON TABLE nexa_platform.purchase_request_expiry_policy_snapshot IS
    'Non-authoritative projection of the centrally verified BC01 purchase-request expiry policy; no row means policy use is unavailable.';

ALTER TABLE nexa_platform.purchase_request_expiry_policy_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE nexa_platform.purchase_request_expiry_policy_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY purchase_request_expiry_policy_snapshot_tenant_workspace_scope
    ON nexa_platform.purchase_request_expiry_policy_snapshot
    USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    )
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    );

REVOKE ALL PRIVILEGES ON TABLE nexa_platform.purchase_request_expiry_policy_snapshot FROM PUBLIC;
REVOKE ALL PRIVILEGES ON TABLE nexa_platform.purchase_request_expiry_policy_snapshot FROM nexa_runtime;
GRANT USAGE ON SCHEMA nexa_platform TO nexa_runtime;
GRANT SELECT ON nexa_platform.purchase_request_expiry_policy_snapshot TO nexa_runtime;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_policy_snapshot_writer') THEN
        RAISE EXCEPTION 'Dedicated nexa_policy_snapshot_writer role must be provisioned before V4';
    END IF;
    GRANT USAGE ON SCHEMA nexa_platform TO nexa_policy_snapshot_writer;
    GRANT SELECT ON nexa_platform.tenant_business_database_identity,
        nexa_platform.tenant_workspace_scope_anchor,
        nexa_platform.purchase_request_expiry_policy_snapshot
        TO nexa_policy_snapshot_writer;
    GRANT INSERT, UPDATE ON nexa_platform.purchase_request_expiry_policy_snapshot
        TO nexa_policy_snapshot_writer;
END
$$;

CREATE OR REPLACE FUNCTION sales.assign_purchase_request_expiry()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_snapshot_revision BIGINT;
    active_snapshot_revision BIGINT;
    expiry_days INTEGER;
BEGIN
    IF NEW.status <> 'DRAFT' THEN
        expected_snapshot_revision := nullif(
            current_setting('app.expected_purchase_request_expiry_policy_revision', true), '')::BIGINT;
        IF expected_snapshot_revision IS NULL OR expected_snapshot_revision < 1 THEN
            RAISE EXCEPTION 'Purchase-request expiry policy was not centrally validated for this transaction'
                USING ERRCODE = '55000';
        END IF;

        SELECT snapshot_revision, purchase_request_expiry_policy_snapshot.expiry_days
          INTO active_snapshot_revision, expiry_days
          FROM nexa_platform.purchase_request_expiry_policy_snapshot
         WHERE tenant_id = NEW.tenant_id
           AND workspace_id = NEW.workspace_id;
        IF NOT FOUND OR active_snapshot_revision <> expected_snapshot_revision THEN
            RAISE EXCEPTION 'Purchase-request expiry policy snapshot is missing or stale'
                USING ERRCODE = '55000';
        END IF;

        IF NEW.expires_at IS NULL THEN
            NEW.expires_at := coalesce(NEW.submitted_at, NEW.created_at, current_timestamp)
                + make_interval(days => expiry_days);
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON FUNCTION sales.assign_purchase_request_expiry() IS
    'Uses only a centrally validated Tenant-local policy snapshot; non-DRAFT commands fail closed without the matching server transaction revision.';
