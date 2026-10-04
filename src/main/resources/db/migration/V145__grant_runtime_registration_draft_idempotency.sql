-- V86 created this technical idempotency table after the historical tenant
-- management grants. Keep the runtime permission limited to the DML used by
-- the public draft adapter; RLS and application authorization remain the
-- authority for registration state.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT
            ON tenant_management.organization_registration_draft_idempotency TO nexa_runtime;
    END IF;
END;
$$;
