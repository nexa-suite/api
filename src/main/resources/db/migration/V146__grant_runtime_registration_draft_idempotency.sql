-- V86 created this table after the historical tenant_management table grant.
-- The draft adapter only reads existing keys and inserts a new result.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT SELECT, INSERT
            ON tenant_management.organization_registration_draft_idempotency TO nexa_runtime;
    END IF;
END;
$$;
