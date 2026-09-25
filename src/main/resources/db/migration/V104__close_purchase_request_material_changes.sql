-- Canonical Purchase Request lifecycle, Buyer material-change evidence, and
-- append-preserving current-line projection. Legacy values remain readable.
ALTER TABLE sales.purchase_request
    DROP CONSTRAINT IF EXISTS ck_purchase_request_status_v2,
    DROP CONSTRAINT IF EXISTS ck_purchase_request_status_v3;

ALTER TABLE sales.purchase_request
    ADD CONSTRAINT ck_purchase_request_status_v3 CHECK (status IN (
        'DRAFT','SUBMITTED','CHANGES_PROPOSED','CONVERTED','REJECTED','WITHDRAWN','EXPIRED',
        'IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','CANCELLED','CONVERTED_TO_ORDER'
    ));

-- Assisted orders record the Sales actor and Customer Account without asserting a Buyer identity.
ALTER TABLE sales.sales_order ALTER COLUMN buyer_membership_id DROP NOT NULL;
ALTER TABLE sales.sales_order
    ADD CONSTRAINT ck_sales_order_buyer_required_unless_manual
    CHECK (order_source = 'MANUAL' OR buyer_membership_id IS NOT NULL);

ALTER TABLE sales.purchase_request_line
    ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMPTZ;

ALTER TABLE sales.purchase_request_line
    DROP CONSTRAINT IF EXISTS uq_purchase_request_catalog_item;

CREATE UNIQUE INDEX IF NOT EXISTS uq_purchase_request_active_catalog_item
    ON sales.purchase_request_line (purchase_request_id, catalog_item_id)
    WHERE superseded_at IS NULL;

CREATE TABLE sales.purchase_request_material_change (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    purchase_request_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    proposed_by_membership_id UUID NOT NULL,
    resolved_by_membership_id UUID,
    proposal_reason TEXT,
    original_snapshot JSONB NOT NULL,
    proposed_snapshot JSONB NOT NULL,
    proposed_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    request_version BIGINT NOT NULL,
    resolved_request_version BIGINT,
    CONSTRAINT fk_pr_material_change_request
        FOREIGN KEY (tenant_id, workspace_id, purchase_request_id)
        REFERENCES sales.purchase_request (tenant_id, workspace_id, id),
    CONSTRAINT fk_pr_material_change_proposer
        FOREIGN KEY (workspace_id, proposed_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_pr_material_change_resolver
        FOREIGN KEY (workspace_id, resolved_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_pr_material_change_status CHECK (status IN ('PROPOSED','ACCEPTED')),
    CONSTRAINT ck_pr_material_change_resolution CHECK (
        (status = 'PROPOSED' AND resolved_at IS NULL AND resolved_by_membership_id IS NULL AND resolved_request_version IS NULL)
        OR (status = 'ACCEPTED' AND resolved_at IS NOT NULL AND resolved_by_membership_id IS NOT NULL AND resolved_request_version IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_pr_material_change_one_proposed
    ON sales.purchase_request_material_change (tenant_id, workspace_id, purchase_request_id)
    WHERE status = 'PROPOSED';

CREATE INDEX ix_pr_material_change_history
    ON sales.purchase_request_material_change (tenant_id, workspace_id, purchase_request_id, proposed_at, id);

ALTER TABLE sales.purchase_request_material_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE sales.purchase_request_material_change FORCE ROW LEVEL SECURITY;
CREATE POLICY v104_pr_material_change_scope ON sales.purchase_request_material_change
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON sales.purchase_request_material_change TO nexa_runtime';
    END IF;
END;
$$;
