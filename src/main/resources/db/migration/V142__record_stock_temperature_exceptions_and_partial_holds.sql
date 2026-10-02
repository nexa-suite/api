-- Temperature observations are evidence and must not be rounded to legacy
-- four- or three-decimal column scales.
ALTER TABLE logistics.temperature_evidence
    ALTER COLUMN value TYPE NUMERIC,
    ALTER COLUMN temperature_celsius TYPE NUMERIC;

ALTER TABLE warehouse.inventory_temperature_evaluation
    ALTER COLUMN observed_value TYPE NUMERIC;

ALTER TABLE logistics.temperature_evidence
    ADD COLUMN reason VARCHAR(2000),
    ADD COLUMN source_evidence_id UUID;

ALTER TABLE logistics.temperature_evidence
    ADD CONSTRAINT fk_temperature_evidence_source_evidence
        FOREIGN KEY (tenant_id, workspace_id, source_evidence_id)
        REFERENCES logistics.temperature_evidence (tenant_id, workspace_id, id),
    ADD CONSTRAINT ck_temperature_evidence_reason
        CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 2000);

CREATE TABLE logistics.stock_temperature_exception (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    root_evidence_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    reason VARCHAR(2000) NOT NULL,
    actor_membership_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    resolution VARCHAR(2000),
    resolved_at TIMESTAMPTZ,
    resolved_by_membership_id UUID,
    CONSTRAINT uq_stock_temperature_exception_scope_id UNIQUE (tenant_id, workspace_id, id),
    CONSTRAINT uq_stock_temperature_exception_root UNIQUE (tenant_id, workspace_id, root_evidence_id),
    CONSTRAINT fk_stock_temperature_exception_evidence
        FOREIGN KEY (tenant_id, workspace_id, root_evidence_id)
        REFERENCES logistics.temperature_evidence (tenant_id, workspace_id, id),
    CONSTRAINT fk_stock_temperature_exception_actor
        FOREIGN KEY (workspace_id, actor_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT fk_stock_temperature_exception_resolver
        FOREIGN KEY (workspace_id, resolved_by_membership_id)
        REFERENCES tenant_management.workspace_membership (workspace_id, id),
    CONSTRAINT ck_stock_temperature_exception_status CHECK (status IN ('OPEN','RESOLVED')),
    CONSTRAINT ck_stock_temperature_exception_reason CHECK (length(btrim(reason)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_stock_temperature_exception_resolution CHECK (
        (status='OPEN' AND resolution IS NULL AND resolved_at IS NULL AND resolved_by_membership_id IS NULL)
        OR (status='RESOLVED' AND resolution IS NOT NULL
            AND length(btrim(resolution)) BETWEEN 1 AND 2000
            AND resolved_at IS NOT NULL AND resolved_by_membership_id IS NOT NULL)
    )
);

CREATE INDEX ix_stock_temperature_exception_scope
    ON logistics.stock_temperature_exception (tenant_id, workspace_id, status, occurred_at DESC, id);

CREATE TRIGGER logistics_stock_temperature_exception_append_only
    BEFORE DELETE ON logistics.stock_temperature_exception FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE warehouse.inventory_temperature_evaluation
    ADD COLUMN blocks_committed_execution BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE logistics.temperature_evidence
    DROP CONSTRAINT ck_temperature_evidence_inventory_lot_status,
    ADD CONSTRAINT ck_temperature_evidence_inventory_lot_status
        CHECK (inventory_lot_status IS NULL OR inventory_lot_status IN ('AVAILABLE','HOLD'));

ALTER TABLE warehouse.inventory_temperature_evaluation
    DROP CONSTRAINT ck_inventory_temperature_evaluation_source_type,
    ADD CONSTRAINT ck_inventory_temperature_evaluation_source_type
        CHECK (source_type IN ('RECEIVING','FULFILLMENT','STOCK_EVIDENCE')),
    ADD CONSTRAINT ck_inventory_temperature_evaluation_stock_evidence
        CHECK (source_type <> 'STOCK_EVIDENCE' OR
               (temperature_evidence_id IS NOT NULL AND expected_lot_version IS NOT NULL
                AND evidence_object_id IS NOT NULL AND observed_value IS NOT NULL
                AND affected_quantity IS NOT NULL AND affected_quantity > 0));

ALTER TABLE warehouse.inventory_temperature_evaluation
    ADD CONSTRAINT uq_inventory_temperature_evaluation_scope_lot_id
        UNIQUE (tenant_id, workspace_id, lot_id, id);

CREATE UNIQUE INDEX uq_inventory_temperature_evaluation_evidence
    ON warehouse.inventory_temperature_evaluation (tenant_id, workspace_id, temperature_evidence_id)
    WHERE temperature_evidence_id IS NOT NULL;

ALTER TABLE warehouse.inventory_lot_disposition
    ADD COLUMN quantity NUMERIC(19,4),
    ADD COLUMN temperature_evaluation_id UUID,
    ADD CONSTRAINT fk_inventory_lot_disposition_temperature_evaluation
        FOREIGN KEY (tenant_id, workspace_id, lot_id, temperature_evaluation_id)
        REFERENCES warehouse.inventory_temperature_evaluation (tenant_id, workspace_id, lot_id, id),
    ADD CONSTRAINT ck_inventory_lot_disposition_quantity
        CHECK ((quantity IS NULL AND temperature_evaluation_id IS NULL)
            OR (quantity IS NOT NULL AND quantity > 0 AND temperature_evaluation_id IS NOT NULL));

CREATE INDEX ix_inventory_lot_disposition_temperature_evaluation
    ON warehouse.inventory_lot_disposition (tenant_id, workspace_id, temperature_evaluation_id, created_at, id)
    WHERE temperature_evaluation_id IS NOT NULL;

ALTER TABLE logistics.stock_temperature_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.stock_temperature_exception FORCE ROW LEVEL SECURITY;
CREATE POLICY v142_stock_temperature_exception_scope ON logistics.stock_temperature_exception
    USING (tenant_id::text=current_setting('app.current_tenant_id',true)
       AND workspace_id::text=current_setting('app.current_workspace_id',true))
    WITH CHECK (tenant_id::text=current_setting('app.current_tenant_id',true)
            AND workspace_id::text=current_setting('app.current_workspace_id',true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_runtime') THEN
        REVOKE DELETE ON logistics.stock_temperature_exception FROM nexa_runtime;
        GRANT SELECT,INSERT,UPDATE ON logistics.stock_temperature_exception TO nexa_runtime;
        GRANT SELECT,INSERT,UPDATE ON warehouse.inventory_temperature_evaluation TO nexa_runtime;
        GRANT SELECT,INSERT ON warehouse.inventory_lot_disposition TO nexa_runtime;
    END IF;
END;
$$;

COMMENT ON TABLE logistics.stock_temperature_exception IS
    'BC-06 source exception for a scoped stock-temperature observation; lot selections and inventory dispositions remain BC-05 facts.';
COMMENT ON COLUMN logistics.temperature_evidence.source_evidence_id IS
    'Optional parent WAREHOUSE temperature evidence that authorized this explicit affected-lot selection.';
COMMENT ON COLUMN warehouse.inventory_lot_disposition.quantity IS
    'Quantity resolved from the linked temperature evaluation; null retains legacy whole-lot dispositions.';
