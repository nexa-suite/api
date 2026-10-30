-- Document generation queue rows retain their owning Tenant and Workspace.
-- Runtime and the dedicated worker must process only the verified Workspace.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_documents_worker')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_payments_worker')
        OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker') THEN
        RAISE EXCEPTION 'Tenant V31 requires runtime, document, payment, and traceability worker roles';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_roles
         WHERE rolname IN ('nexa_runtime', 'nexa_business_documents_worker',
                           'nexa_payments_worker', 'nexa_business_traceability_worker')
           AND (rolsuper OR rolbypassrls)
    ) THEN
        RAISE EXCEPTION 'Tenant V31 roles must not bypass row-level security';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_policy
         WHERE polrelid = 'business_documents.document_generation_request'::regclass
    ) THEN
        RAISE EXCEPTION 'Tenant V31 found an unexpected document-generation policy that could widen access';
    END IF;
END;
$$;

ALTER TABLE business_documents.document_generation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE business_documents.document_generation_request FORCE ROW LEVEL SECURITY;

CREATE POLICY document_generation_request_verified_workspace_scope
    ON business_documents.document_generation_request
    FOR ALL
    TO nexa_runtime, nexa_business_documents_worker, nexa_payments_worker,
       nexa_business_traceability_worker
    USING (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    )
    WITH CHECK (
        tenant_id::text = nullif(current_setting('app.current_tenant_id', true), '')
        AND workspace_id::text = nullif(current_setting('app.current_workspace_id', true), '')
    );

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_class relation
          JOIN pg_namespace schema_row ON schema_row.oid = relation.relnamespace
         WHERE schema_row.nspname = 'business_documents'
           AND relation.relname = 'document_generation_request'
           AND relation.relrowsecurity
           AND relation.relforcerowsecurity
    ) THEN
        RAISE EXCEPTION 'Tenant V31 failed to force row security on document_generation_request';
    END IF;

    IF (SELECT count(*)
          FROM pg_policy
         WHERE polrelid = 'business_documents.document_generation_request'::regclass) <> 1
        OR NOT EXISTS (
            SELECT 1
              FROM pg_policies
             WHERE schemaname = 'business_documents'
               AND tablename = 'document_generation_request'
               AND policyname = 'document_generation_request_verified_workspace_scope'
               AND cmd = 'ALL'
               AND roles @> ARRAY['nexa_runtime'::name, 'nexa_business_documents_worker'::name,
                                  'nexa_payments_worker'::name,
                                  'nexa_business_traceability_worker'::name]
               AND roles <@ ARRAY['nexa_runtime'::name, 'nexa_business_documents_worker'::name,
                                  'nexa_payments_worker'::name,
                                  'nexa_business_traceability_worker'::name]
               AND qual LIKE '%app.current_tenant_id%'
               AND qual LIKE '%app.current_workspace_id%'
               AND with_check LIKE '%app.current_tenant_id%'
               AND with_check LIKE '%app.current_workspace_id%'
        ) THEN
        RAISE EXCEPTION 'Tenant V31 must enforce one exact Tenant/Workspace policy for all authorized roles';
    END IF;
END;
$$;
