CREATE TABLE logistics.delivery_execution_temperature_evidence (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,workspace_id uuid NOT NULL,delivery_id uuid NOT NULL,
 attempt_id uuid,fulfillment_line_id uuid NOT NULL,sku_id uuid NOT NULL,affected_quantity numeric(19,6) NOT NULL CHECK(affected_quantity>0),
 quantity_unit varchar(40) NOT NULL,temperature_unit varchar(16) NOT NULL DEFAULT 'CELSIUS' CHECK(temperature_unit='CELSIUS'),value_celsius numeric(19,6) NOT NULL CHECK(value_celsius>-1000 AND value_celsius<1000),
 minimum_celsius numeric(19,6) NOT NULL,maximum_celsius numeric(19,6) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN('WITHIN_RANGE','OUT_OF_RANGE')),
 actor_membership_id uuid NOT NULL,occurred_at timestamptz NOT NULL,recorded_at timestamptz NOT NULL,
 evidence_object_id uuid,source_incident_id uuid,delivery_version bigint NOT NULL CHECK(delivery_version>=0),
 UNIQUE(tenant_id,workspace_id,delivery_id,id),
 FOREIGN KEY(tenant_id,workspace_id,delivery_id) REFERENCES logistics.delivery(tenant_id,workspace_id,id),
 FOREIGN KEY(tenant_id,workspace_id,delivery_id,source_incident_id) REFERENCES logistics.driver_delivery_incident(tenant_id,workspace_id,delivery_id,id),
 FOREIGN KEY(tenant_id,workspace_id,fulfillment_line_id) REFERENCES logistics.fulfillment_line(tenant_id,workspace_id,id),
 FOREIGN KEY(tenant_id,workspace_id,attempt_id,delivery_id) REFERENCES logistics.delivery_attempt(tenant_id,workspace_id,id,delivery_id),
 CHECK(minimum_celsius<=maximum_celsius),
 CHECK((status='WITHIN_RANGE' AND value_celsius BETWEEN minimum_celsius AND maximum_celsius)
    OR (status='OUT_OF_RANGE' AND (value_celsius<minimum_celsius OR value_celsius>maximum_celsius)
        AND evidence_object_id IS NOT NULL AND source_incident_id IS NOT NULL))
);
CREATE TABLE logistics.delivery_execution_hold (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,workspace_id uuid NOT NULL,delivery_id uuid NOT NULL,
 reading_id uuid NOT NULL,exception_id uuid NOT NULL,
 UNIQUE(tenant_id,workspace_id,delivery_id,id),UNIQUE(tenant_id,workspace_id,exception_id),
 FOREIGN KEY(tenant_id,workspace_id,delivery_id,reading_id) REFERENCES logistics.delivery_execution_temperature_evidence(tenant_id,workspace_id,delivery_id,id),
 FOREIGN KEY(tenant_id,workspace_id,delivery_id,exception_id) REFERENCES logistics.operational_exception_case(tenant_id,workspace_id,delivery_id,id)
);
CREATE TABLE logistics.delivery_execution_disposition (
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,workspace_id uuid NOT NULL,delivery_id uuid NOT NULL,
 hold_id uuid NOT NULL,sequence integer NOT NULL CHECK(sequence>=1),
 disposition varchar(20) NOT NULL CHECK(disposition IN('RELEASE','CONTINUE_HOLD','REJECT','WASTE')),
 actor_membership_id uuid NOT NULL,occurred_at timestamptz NOT NULL,reason varchar(2000) NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 2000),
 delivery_version bigint NOT NULL CHECK(delivery_version>=0),
 UNIQUE(tenant_id,workspace_id,hold_id,sequence),
 FOREIGN KEY(tenant_id,workspace_id,delivery_id,hold_id) REFERENCES logistics.delivery_execution_hold(tenant_id,workspace_id,delivery_id,id)
);
CREATE INDEX ix_execution_temperature_delivery ON logistics.delivery_execution_temperature_evidence(tenant_id,workspace_id,delivery_id,recorded_at,id);
CREATE INDEX ix_execution_disposition_current ON logistics.delivery_execution_disposition(tenant_id,workspace_id,hold_id,sequence DESC);
DO $$ DECLARE item text; BEGIN
 FOREACH item IN ARRAY ARRAY['delivery_execution_temperature_evidence','delivery_execution_hold','delivery_execution_disposition'] LOOP
  EXECUTE format('ALTER TABLE logistics.%I ENABLE ROW LEVEL SECURITY',item);
  EXECUTE format('ALTER TABLE logistics.%I FORCE ROW LEVEL SECURITY',item);
  EXECUTE format('CREATE POLICY v139_scope ON logistics.%I USING(tenant_id::text=current_setting(''app.current_tenant_id'',true) AND workspace_id::text=current_setting(''app.current_workspace_id'',true)) WITH CHECK(tenant_id::text=current_setting(''app.current_tenant_id'',true) AND workspace_id::text=current_setting(''app.current_workspace_id'',true))',item);
  EXECUTE format('CREATE TRIGGER execution_fact_append_only BEFORE UPDATE OR DELETE ON logistics.%I FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation()',item);
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='nexa_runtime') THEN
   EXECUTE format('GRANT SELECT,INSERT ON logistics.%I TO nexa_runtime',item);
  END IF;
 END LOOP;
END $$;
