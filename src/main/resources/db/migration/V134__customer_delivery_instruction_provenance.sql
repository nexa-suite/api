CREATE TABLE logistics.customer_instruction_set (
 tenant_id uuid NOT NULL, workspace_id uuid NOT NULL, sales_order_id uuid NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 PRIMARY KEY(tenant_id,workspace_id,sales_order_id)
);
CREATE TABLE logistics.customer_instruction_revision (
 revision_id uuid PRIMARY KEY,tenant_id uuid NOT NULL,workspace_id uuid NOT NULL,sales_order_id uuid NOT NULL,
 instruction_id uuid NOT NULL,instruction_version bigint NOT NULL CHECK(instruction_version>=1),
 set_version bigint NOT NULL CHECK(set_version>=1),kind varchar(40) NOT NULL,content varchar(2000) NOT NULL,
 source_kind varchar(40) NOT NULL,source_reference varchar(500),recorded_by_membership_id uuid NOT NULL,
 recorded_at timestamptz NOT NULL,request_hash char(64) NOT NULL,
 UNIQUE(tenant_id,workspace_id,sales_order_id,instruction_id,instruction_version),
 UNIQUE(tenant_id,workspace_id,revision_id),
 FOREIGN KEY(tenant_id,workspace_id,sales_order_id) REFERENCES logistics.customer_instruction_set(tenant_id,workspace_id,sales_order_id),
 CHECK(kind IN ('NORMAL','COLD_CHAIN','ACCESS_RESTRICTION','SPECIAL_UNLOADING','CUSTOMER_SAFETY','GOODS_HANDLING')),
 CHECK(source_kind IN ('BUYER','CUSTOMER_REPORTED_BY_SALES')),
 CHECK(source_kind<>'CUSTOMER_REPORTED_BY_SALES' OR (source_reference IS NOT NULL AND length(btrim(source_reference)) BETWEEN 1 AND 500)),
 CHECK(length(btrim(content)) BETWEEN 1 AND 2000), CHECK(request_hash ~ '^[0-9a-f]{64}$')
);
CREATE TRIGGER logistics_customer_instruction_revision_append_only BEFORE UPDATE OR DELETE ON logistics.customer_instruction_revision FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
ALTER TABLE logistics.delivery_instruction_revision ADD COLUMN source_kind varchar(40) NOT NULL DEFAULT 'OPERATIONAL_DISPATCH',ADD COLUMN source_reference varchar(500),ADD COLUMN source_revision_id uuid;
ALTER TABLE logistics.delivery_instruction_revision ADD CONSTRAINT delivery_instruction_source_revision_scope
 FOREIGN KEY(tenant_id,workspace_id,source_revision_id) REFERENCES logistics.customer_instruction_revision(tenant_id,workspace_id,revision_id);
ALTER TABLE logistics.delivery_instruction_revision ADD CONSTRAINT delivery_instruction_source CHECK(source_kind IN ('OPERATIONAL_DISPATCH','BUYER','CUSTOMER_REPORTED_BY_SALES'));
ALTER TABLE logistics.customer_instruction_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.customer_instruction_set FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.customer_instruction_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.customer_instruction_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY v134_customer_instruction_set_scope ON logistics.customer_instruction_set USING(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true)) WITH CHECK(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true));
CREATE POLICY v134_customer_instruction_revision_scope ON logistics.customer_instruction_revision USING(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true)) WITH CHECK(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true));
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='nexa_runtime') THEN
  GRANT SELECT,INSERT,UPDATE ON logistics.customer_instruction_set TO nexa_runtime;
  GRANT SELECT,INSERT ON logistics.customer_instruction_revision TO nexa_runtime;
 END IF;
END $$;
