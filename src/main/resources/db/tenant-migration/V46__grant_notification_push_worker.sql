-- Provision the isolated role before migration. Local installations may use
-- NOLOGIN; cloud activation supplies a separately managed LOGIN credential.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_notification_push_worker') THEN
        RAISE EXCEPTION 'nexa_notification_push_worker must be provisioned before Tenant migration V46';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles r WHERE r.rolname = 'nexa_notification_push_worker'
            AND (r.rolinherit OR r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls
                OR EXISTS (SELECT 1 FROM pg_auth_members m WHERE m.member=r.oid OR m.roleid=r.oid)
                OR EXISTS (SELECT 1 FROM pg_database d WHERE d.datdba=r.oid)
                OR EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspowner=r.oid OR has_schema_privilege(r.oid,n.oid,'CREATE'))
                OR EXISTS (SELECT 1 FROM pg_class c WHERE c.relowner=r.oid)
                OR EXISTS (SELECT 1 FROM pg_proc p WHERE p.proowner=r.oid))) THEN
        RAISE EXCEPTION 'Tenant V46 requires an isolated notification worker role';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_%'
              AND c.relkind IN ('r','p','v','m','f')
              AND (has_table_privilege('nexa_notification_push_worker',c.oid,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                   OR has_any_column_privilege('nexa_notification_push_worker',c.oid,'SELECT,INSERT,UPDATE,REFERENCES'))) THEN
        RAISE EXCEPTION 'Tenant V46 requires a notification worker without pre-existing table grants';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_policy WHERE polrelid = 'integration.outbox_event'::regclass) THEN
        RAISE EXCEPTION 'Tenant V46 found existing outbox RLS policies requiring explicit review';
    END IF;
END $$;

GRANT USAGE ON SCHEMA nexa_platform, integration, notifications, tenant_management, sales, payments, logistics
    TO nexa_notification_push_worker;
GRANT SELECT (singleton, tenant_id, database_identity) ON nexa_platform.tenant_business_database_identity TO nexa_notification_push_worker;
GRANT SELECT (tenant_id, workspace_id) ON nexa_platform.tenant_workspace_scope_anchor TO nexa_notification_push_worker;
GRANT SELECT (event_id, event_type, aggregate_type, aggregate_id, tenant_id, workspace_id, occurred_at, correlation_id, causation_id, schema_version, payload, created_at),
      INSERT (event_id, event_type, aggregate_type, aggregate_id, tenant_id, workspace_id, occurred_at, correlation_id, causation_id, schema_version, payload),
      UPDATE (event_id) ON integration.outbox_event TO nexa_notification_push_worker;
GRANT SELECT (consumer_name, event_id, tenant_id, workspace_id),
      INSERT (consumer_name, event_id, tenant_id, workspace_id, processed_at, result)
    ON integration.inbox_event TO nexa_notification_push_worker;
-- ON CONFLICT inference reads the inbox uniqueness columns.
GRANT SELECT (event_id, recipient_membership_id) ON notifications.inbox_item
    TO nexa_notification_push_worker;
GRANT INSERT (id, tenant_id, workspace_id, recipient_membership_id, event_id, category, title, message, deep_link, subject_type, subject_id, created_at, read_at)
    ON notifications.inbox_item TO nexa_notification_push_worker;
GRANT SELECT (workspace_id, event_category, channel, enabled) ON tenant_management.notification_preference TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, recipient_membership_id, installation_id, platform, surface, status, created_at, updated_at, version, provider_destination_ciphertext, provider_destination_key_version),
      UPDATE (status, updated_at, version, provider_destination_ciphertext, provider_destination_key_version)
    ON notifications.push_subscription TO nexa_notification_push_worker;
GRANT SELECT (tenant_id, workspace_id, subscription_id, event_id, status, claim_token, lease_until),
      INSERT (tenant_id, workspace_id, subscription_id, event_id, delivery_key, status, claim_token, lease_until, created_at, updated_at),
      UPDATE (status, claim_token, lease_until, updated_at)
    ON notifications.push_delivery_claim TO nexa_notification_push_worker;
GRANT SELECT (tenant_id, workspace_id, subscription_id, event_id, status, attempt_number),
      INSERT (id, tenant_id, workspace_id, subscription_id, event_id, event_type, status, provider_code, error, attempt_number, created_at)
    ON notifications.push_delivery_attempt TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, client_account_id, version) ON sales.purchase_request TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, client_account_id, version, commercial_commitment_id) ON sales.sales_order TO nexa_notification_push_worker;
GRANT SELECT (tenant_id, workspace_id, client_account_id, workspace_membership_id, status) ON sales.client_account_membership TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, client_account_id)
    ON payments.receivable TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, inventory_reservation_id, sales_order_id, client_account_id, version)
    ON logistics.dispatch_order TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, delivery_id, dispatch_order_id, status, sealed_at)
    ON logistics.proof_of_delivery TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, fulfillment_id) ON logistics.delivery TO nexa_notification_push_worker;
GRANT SELECT (id, tenant_id, workspace_id, sales_order_id) ON logistics.fulfillment TO nexa_notification_push_worker;

-- Preserve existing outbox access for established roles while fencing the new
-- notification worker to its current Workspace and approved source events.
ALTER TABLE integration.outbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.outbox_event FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_existing_access ON integration.outbox_event AS PERMISSIVE FOR ALL TO PUBLIC
    USING (true) WITH CHECK (true);
CREATE POLICY outbox_notification_worker_select ON integration.outbox_event AS RESTRICTIVE FOR SELECT
    TO nexa_notification_push_worker USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        AND event_type IN ('PURCHASE_REQUEST_SUBMITTED', 'PURCHASE_REQUEST_APPROVED', 'SALES_ORDER_CONFIRMED',
            'DISPATCH_DELIVERED', 'DeliveryCompleted.v1', 'DELIVERY_COMPLETED', 'POD_COMPLETED',
            'PAYMENT_SUCCEEDED', 'NOTIFICATION_PUSH_DELIVERY_REQUESTED')
    );
CREATE POLICY outbox_notification_worker_update ON integration.outbox_event AS RESTRICTIVE FOR UPDATE
    TO nexa_notification_push_worker USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        AND event_type IN ('PURCHASE_REQUEST_SUBMITTED', 'PURCHASE_REQUEST_APPROVED', 'SALES_ORDER_CONFIRMED',
            'DISPATCH_DELIVERED', 'DeliveryCompleted.v1', 'DELIVERY_COMPLETED', 'POD_COMPLETED',
            'PAYMENT_SUCCEEDED', 'NOTIFICATION_PUSH_DELIVERY_REQUESTED')
    ) WITH CHECK (false);
CREATE POLICY outbox_notification_worker_insert ON integration.outbox_event AS RESTRICTIVE FOR INSERT
    TO nexa_notification_push_worker WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
        AND event_type = 'NOTIFICATION_PUSH_DELIVERY_REQUESTED'
    );
