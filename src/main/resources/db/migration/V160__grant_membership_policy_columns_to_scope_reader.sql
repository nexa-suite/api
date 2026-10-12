-- The READY-workspace reader's RLS policies inspect these identity columns.
-- Keep its direct permission narrower than table-level membership access.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_business_documents_scope_reader') THEN
        GRANT SELECT (id, workspace_id, user_id)
            ON tenant_management.workspace_membership
            TO nexa_business_documents_scope_reader;
    END IF;
END;
$$;
