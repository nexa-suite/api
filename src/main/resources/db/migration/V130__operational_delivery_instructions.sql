ALTER TABLE logistics.delivery
    ADD COLUMN instruction_set_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_delivery_instruction_set_version CHECK (instruction_set_version >= 0);

CREATE TABLE logistics.delivery_instruction_revision (
    revision_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    instruction_id UUID NOT NULL,
    instruction_version BIGINT NOT NULL,
    kind VARCHAR(40) NOT NULL,
    content VARCHAR(2000) NOT NULL,
    authored_by_membership_id UUID NOT NULL,
    authored_at TIMESTAMPTZ NOT NULL,
    request_hash CHAR(64) NOT NULL,
    published_delivery_version BIGINT NOT NULL,
    instruction_set_version BIGINT NOT NULL,
    CONSTRAINT uq_delivery_instruction_revision_scope_version
        UNIQUE (tenant_id, workspace_id, instruction_id, instruction_version),
    CONSTRAINT uq_delivery_instruction_revision_delivery_version
        UNIQUE (tenant_id, workspace_id, delivery_id, instruction_id, instruction_version),
    CONSTRAINT uq_delivery_instruction_revision_scope_delivery
        UNIQUE (tenant_id, workspace_id, revision_id),
    CONSTRAINT fk_delivery_instruction_revision_delivery
        FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT ck_delivery_instruction_revision_version CHECK (instruction_version >= 1),
    CONSTRAINT ck_delivery_instruction_revision_kind CHECK (kind IN (
        'NORMAL', 'COLD_CHAIN', 'ACCESS_RESTRICTION', 'SPECIAL_UNLOADING', 'CUSTOMER_SAFETY', 'GOODS_HANDLING')),
    CONSTRAINT ck_delivery_instruction_revision_content CHECK (length(btrim(content)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_delivery_instruction_revision_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_delivery_instruction_revision_delivery_version CHECK (published_delivery_version >= 0),
    CONSTRAINT ck_delivery_instruction_revision_set_version CHECK (instruction_set_version >= 1)
);

CREATE INDEX ix_delivery_instruction_revision_current
    ON logistics.delivery_instruction_revision
       (tenant_id, workspace_id, delivery_id, instruction_id, instruction_version DESC);

CREATE TRIGGER logistics_delivery_instruction_revision_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_instruction_revision FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

CREATE TABLE logistics.delivery_instruction_acknowledgement (
    acknowledgement_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    instruction_id UUID NOT NULL,
    instruction_version BIGINT NOT NULL,
    instruction_set_version BIGINT NOT NULL,
    kind_snapshot VARCHAR(40) NOT NULL,
    content_snapshot VARCHAR(2000) NOT NULL,
    acknowledged_by_membership_id UUID NOT NULL,
    acknowledged_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_delivery_instruction_ack_command_item
        UNIQUE (tenant_id, workspace_id, acknowledged_by_membership_id, idempotency_key, instruction_id),
    CONSTRAINT fk_delivery_instruction_ack_delivery
        FOREIGN KEY (tenant_id, workspace_id, delivery_id)
        REFERENCES logistics.delivery (tenant_id, workspace_id, id),
    CONSTRAINT fk_delivery_instruction_ack_revision
        FOREIGN KEY (tenant_id, workspace_id, delivery_id, instruction_id, instruction_version)
        REFERENCES logistics.delivery_instruction_revision
                   (tenant_id, workspace_id, delivery_id, instruction_id, instruction_version),
    CONSTRAINT ck_delivery_instruction_ack_version CHECK (instruction_version >= 1),
    CONSTRAINT ck_delivery_instruction_ack_set_version CHECK (instruction_set_version >= 1),
    CONSTRAINT ck_delivery_instruction_ack_critical CHECK (kind_snapshot <> 'NORMAL'),
    CONSTRAINT ck_delivery_instruction_ack_kind CHECK (kind_snapshot IN (
        'COLD_CHAIN', 'ACCESS_RESTRICTION', 'SPECIAL_UNLOADING', 'CUSTOMER_SAFETY', 'GOODS_HANDLING')),
    CONSTRAINT ck_delivery_instruction_ack_content CHECK (length(btrim(content_snapshot)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_delivery_instruction_ack_key CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 160),
    CONSTRAINT ck_delivery_instruction_ack_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_delivery_instruction_ack_current
    ON logistics.delivery_instruction_acknowledgement
       (tenant_id, workspace_id, delivery_id, instruction_id, instruction_version,
        acknowledged_by_membership_id, acknowledged_at DESC);

CREATE TRIGGER logistics_delivery_instruction_ack_append_only
    BEFORE UPDATE OR DELETE ON logistics.delivery_instruction_acknowledgement FOR EACH ROW
    EXECUTE FUNCTION sales.prevent_append_only_mutation();

ALTER TABLE logistics.delivery_instruction_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_instruction_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY v130_delivery_instruction_revision_scope ON logistics.delivery_instruction_revision
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

ALTER TABLE logistics.delivery_instruction_acknowledgement ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.delivery_instruction_acknowledgement FORCE ROW LEVEL SECURITY;
CREATE POLICY v130_delivery_instruction_ack_scope ON logistics.delivery_instruction_acknowledgement
    USING (tenant_id::text = current_setting('app.current_tenant_id', true)
       AND workspace_id::text = current_setting('app.current_workspace_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.current_tenant_id', true)
            AND workspace_id::text = current_setting('app.current_workspace_id', true));

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT ON logistics.delivery_instruction_revision,
            logistics.delivery_instruction_acknowledgement TO nexa_runtime;
    END IF;
END;
$$;
