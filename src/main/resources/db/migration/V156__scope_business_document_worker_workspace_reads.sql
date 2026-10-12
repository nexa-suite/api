-- BC-09 scope discovery is owned by a central read-only login with ID-only access.
-- Its GUC is defense in depth; current_user and the READY predicates are required.
CREATE POLICY business_documents_scope_reader_workspace
    ON tenant_management.workspace
    FOR SELECT
    USING (
        current_user = 'nexa_business_documents_scope_reader'
        AND current_setting('app.cross_scope_workspace_scan', true) = 'true'
    );
CREATE POLICY business_documents_scope_reader_ready_workspace
    ON tenant_management.workspace
    AS RESTRICTIVE
    FOR SELECT
    USING (
        current_user <> 'nexa_business_documents_scope_reader'
        OR (current_setting('app.cross_scope_workspace_scan', true) = 'true'
            AND status = 'ACTIVE'
        AND EXISTS (
            SELECT 1
              FROM tenant_management.tenant_business_database_binding binding
              JOIN tenant_management.tenant_business_database_workspace_anchor_task anchor
                ON anchor.tenant_id = binding.tenant_id
             WHERE binding.tenant_id = workspace.tenant_id
               AND anchor.workspace_id = workspace.id
               AND binding.lifecycle_state = 'READY'
               AND anchor.status = 'READY'
        ))
    );

CREATE POLICY business_documents_scope_reader_binding
    ON tenant_management.tenant_business_database_binding
    FOR SELECT
    USING (
        current_user = 'nexa_business_documents_scope_reader'
        AND current_setting('app.cross_scope_workspace_scan', true) = 'true'
    );
CREATE POLICY business_documents_scope_reader_ready_binding
    ON tenant_management.tenant_business_database_binding
    AS RESTRICTIVE
    FOR SELECT
    USING (
        current_user <> 'nexa_business_documents_scope_reader'
        OR (current_setting('app.cross_scope_workspace_scan', true) = 'true'
            AND lifecycle_state = 'READY')
    );

CREATE POLICY business_documents_scope_reader_workspace_anchor
    ON tenant_management.tenant_business_database_workspace_anchor_task
    FOR SELECT
    USING (
        current_user = 'nexa_business_documents_scope_reader'
        AND current_setting('app.cross_scope_workspace_scan', true) = 'true'
    );
CREATE POLICY business_documents_scope_reader_ready_workspace_anchor
    ON tenant_management.tenant_business_database_workspace_anchor_task
    AS RESTRICTIVE
    FOR SELECT
    USING (
        current_user <> 'nexa_business_documents_scope_reader'
        OR (current_setting('app.cross_scope_workspace_scan', true) = 'true'
            AND status = 'READY'
        AND EXISTS (
            SELECT 1 FROM tenant_management.tenant_business_database_binding binding
             WHERE binding.tenant_id = tenant_business_database_workspace_anchor_task.tenant_id
               AND binding.lifecycle_state = 'READY'
        ))
    );

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_documents_scope_reader') THEN
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO nexa_business_documents_scope_reader', current_database());
        GRANT USAGE ON SCHEMA tenant_management TO nexa_business_documents_scope_reader;
        GRANT SELECT (tenant_id, id, status) ON tenant_management.workspace
            TO nexa_business_documents_scope_reader;
        GRANT SELECT (tenant_id, lifecycle_state) ON tenant_management.tenant_business_database_binding
            TO nexa_business_documents_scope_reader;
        GRANT SELECT (tenant_id, workspace_id, status)
            ON tenant_management.tenant_business_database_workspace_anchor_task
            TO nexa_business_documents_scope_reader;
    END IF;
END;
$$;
