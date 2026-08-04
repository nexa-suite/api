-- Runtime application paths use IAM and audit infrastructure as well as the
-- business schemas granted in V59. Keep the grants explicit and scoped to the
-- local runtime principal; the migrator remains the owner of the objects.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT USAGE ON SCHEMA iam, audit TO nexa_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA iam, audit TO nexa_runtime;
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA iam, audit TO nexa_runtime;
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA iam, audit GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nexa_runtime';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA iam, audit GRANT USAGE, SELECT ON SEQUENCES TO nexa_runtime';
    END IF;
END;
$$;
