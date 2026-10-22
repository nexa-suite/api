-- A physical Tenant database owns only its own event receipts. Fence direct
-- worker access to the explicit Tenant/Workspace context and remove the unused
-- broad runtime grant inherited from the immutable V3 baseline.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'Tenant V28 requires the runtime and traceability worker roles';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM integration.inbox_event receipt
          JOIN integration.outbox_event event ON event.event_id = receipt.event_id
         WHERE receipt.tenant_id IS DISTINCT FROM event.tenant_id
            OR receipt.workspace_id IS DISTINCT FROM event.workspace_id
    ) OR EXISTS (
        SELECT 1
          FROM integration.inbox_event first_receipt
          JOIN integration.inbox_event second_receipt
            ON second_receipt.event_id = first_receipt.event_id
           AND second_receipt.consumer_name > first_receipt.consumer_name
         WHERE first_receipt.tenant_id IS DISTINCT FROM second_receipt.tenant_id
            OR first_receipt.workspace_id IS DISTINCT FROM second_receipt.workspace_id
    ) THEN
        RAISE EXCEPTION 'Tenant V28 found inbox receipts whose Tenant/Workspace scope differs from their source event';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_policy p
         WHERE p.polrelid = 'integration.inbox_event'::regclass
           AND p.polname NOT IN (
               'inbox_event_direct_scope_select',
               'inbox_event_direct_scope_insert'
           )
    ) THEN
        RAISE EXCEPTION 'Tenant V28 found an unexpected inbox policy that could widen access';
    END IF;
END;
$$;

ALTER TABLE integration.inbox_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration.inbox_event FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS inbox_event_direct_scope_select ON integration.inbox_event;
CREATE POLICY inbox_event_direct_scope_select
    ON integration.inbox_event
    FOR SELECT
    USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    );

DROP POLICY IF EXISTS inbox_event_direct_scope_insert ON integration.inbox_event;
CREATE POLICY inbox_event_direct_scope_insert
    ON integration.inbox_event
    FOR INSERT
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    );

REVOKE ALL PRIVILEGES ON TABLE integration.inbox_event FROM PUBLIC, nexa_runtime;
DO $$
DECLARE
    inbox_column name;
BEGIN
    FOR inbox_column IN
        SELECT attname
          FROM pg_attribute
         WHERE attrelid = 'integration.inbox_event'::regclass
           AND attnum > 0
           AND NOT attisdropped
    LOOP
        EXECUTE format(
            'REVOKE ALL PRIVILEGES (%I) ON TABLE integration.inbox_event FROM PUBLIC, nexa_runtime',
            inbox_column);
    END LOOP;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace ns ON ns.oid = relation.relnamespace
         WHERE ns.nspname = 'integration'
           AND relation.relname = 'inbox_event'
           AND relation.relrowsecurity
           AND relation.relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'Tenant V28 failed to force row security on integration.inbox_event';
    END IF;

    IF has_table_privilege('nexa_runtime', 'integration.inbox_event', 'SELECT')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'INSERT')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'UPDATE')
        OR has_table_privilege('nexa_runtime', 'integration.inbox_event', 'DELETE')
        OR has_any_column_privilege('nexa_runtime', 'integration.inbox_event', 'SELECT')
        OR has_any_column_privilege('nexa_runtime', 'integration.inbox_event', 'INSERT')
        OR has_any_column_privilege('nexa_runtime', 'integration.inbox_event', 'UPDATE') THEN
        RAISE EXCEPTION 'Tenant V28 failed to remove runtime access to integration.inbox_event';
    END IF;

    IF NOT has_column_privilege('nexa_business_traceability_worker',
            'integration.inbox_event', 'tenant_id', 'SELECT')
        OR NOT has_column_privilege('nexa_business_traceability_worker',
            'integration.inbox_event', 'processed_at', 'INSERT')
        OR has_table_privilege('nexa_business_traceability_worker',
            'integration.inbox_event', 'SELECT')
        OR has_table_privilege('nexa_business_traceability_worker',
            'integration.inbox_event', 'INSERT') THEN
        RAISE EXCEPTION 'Tenant V28 changed the traceability worker column-level inbox grants';
    END IF;
END;
$$;
