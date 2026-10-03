CREATE TABLE logistics.driver_workday (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, workspace_id uuid NOT NULL,
 actor_membership_id uuid NOT NULL, version bigint NOT NULL CHECK(version>=0),
 status varchar(32) NOT NULL CHECK(status IN ('ACTIVE','LOCATION_UNAVAILABLE','CLOSED')),
 started_at timestamptz NOT NULL, ended_at timestamptz,
 UNIQUE(tenant_id,workspace_id,id),
 CHECK((status='CLOSED' AND ended_at IS NOT NULL) OR (status<>'CLOSED' AND ended_at IS NULL))
);
CREATE UNIQUE INDEX driver_workday_one_active ON logistics.driver_workday(tenant_id,workspace_id,actor_membership_id) WHERE status<>'CLOSED';
CREATE TABLE logistics.driver_workday_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, workspace_id uuid NOT NULL,
 actor_membership_id uuid NOT NULL,workday_id uuid NOT NULL,workday_version bigint NOT NULL,
 action varchar(32) NOT NULL,status varchar(32) NOT NULL,
 started_at timestamptz NOT NULL,ended_at timestamptz,occurred_at timestamptz NOT NULL,
 idempotency_key varchar(160) NOT NULL,request_hash char(64) NOT NULL,
 UNIQUE(tenant_id,workspace_id,actor_membership_id,idempotency_key),
 FOREIGN KEY(tenant_id,workspace_id,workday_id) REFERENCES logistics.driver_workday(tenant_id,workspace_id,id),
 CHECK(action IN ('START','END','AVAILABLE','UNAVAILABLE')),
 CHECK(status IN ('ACTIVE','LOCATION_UNAVAILABLE','CLOSED')),
 CHECK(workday_version>=0),CHECK(length(btrim(idempotency_key)) BETWEEN 1 AND 160),
 CHECK(request_hash ~ '^[0-9a-f]{64}$')
);
CREATE TRIGGER logistics_driver_workday_event_append_only BEFORE UPDATE OR DELETE ON logistics.driver_workday_event FOR EACH ROW EXECUTE FUNCTION sales.prevent_append_only_mutation();
CREATE TABLE logistics.driver_coordinate (
 sample_id uuid NOT NULL, tenant_id uuid NOT NULL,workspace_id uuid NOT NULL,
 actor_membership_id uuid NOT NULL,workday_id uuid NOT NULL,
 latitude double precision NOT NULL CHECK(latitude BETWEEN -90 AND 90),
 longitude double precision NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 accuracy_meters double precision NOT NULL CHECK(accuracy_meters>=0 AND accuracy_meters<'Infinity'::float8),
 captured_at timestamptz NOT NULL,received_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,workspace_id,sample_id),
 FOREIGN KEY(tenant_id,workspace_id,workday_id) REFERENCES logistics.driver_workday(tenant_id,workspace_id,id),
 CHECK(expires_at=captured_at+interval '24 hours')
);
CREATE INDEX driver_coordinate_latest ON logistics.driver_coordinate(tenant_id,workspace_id,actor_membership_id,captured_at DESC);
CREATE INDEX driver_coordinate_expiry ON logistics.driver_coordinate(expires_at);
ALTER TABLE logistics.driver_workday ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.driver_workday FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.driver_workday_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.driver_workday_event FORCE ROW LEVEL SECURITY;
ALTER TABLE logistics.driver_coordinate ENABLE ROW LEVEL SECURITY;
ALTER TABLE logistics.driver_coordinate FORCE ROW LEVEL SECURITY;
CREATE POLICY v135_workday_scope ON logistics.driver_workday USING(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true)) WITH CHECK(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true));
CREATE POLICY v135_workday_event_scope ON logistics.driver_workday_event USING(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true)) WITH CHECK(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true));
CREATE POLICY v135_coordinate_scope ON logistics.driver_coordinate USING(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true)) WITH CHECK(tenant_id::text=current_setting('app.current_tenant_id',true) AND workspace_id::text=current_setting('app.current_workspace_id',true));
-- Narrow retention-only capability: no parameters, no coordinate result, no general RLS bypass grant.
CREATE FUNCTION logistics.purge_expired_driver_coordinates() RETURNS void
 LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog AS $$
 DELETE FROM logistics.driver_coordinate WHERE expires_at<=clock_timestamp();
$$;
REVOKE ALL ON FUNCTION logistics.purge_expired_driver_coordinates() FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='nexa_runtime') THEN
  GRANT SELECT,INSERT,UPDATE ON logistics.driver_workday TO nexa_runtime;
  GRANT SELECT,INSERT ON logistics.driver_workday_event,logistics.driver_coordinate TO nexa_runtime;
  GRANT EXECUTE ON FUNCTION logistics.purge_expired_driver_coordinates() TO nexa_runtime;
 END IF;
END $$;
