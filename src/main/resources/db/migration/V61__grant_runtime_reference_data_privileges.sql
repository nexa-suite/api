-- The authenticated buyer profile reads immutable geography and road-type
-- reference data. Keep this read-only grant separate from business write
-- privileges so the runtime principal can render address forms safely.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT USAGE ON SCHEMA reference_data TO nexa_runtime;
        GRANT SELECT ON ALL TABLES IN SCHEMA reference_data TO nexa_runtime;
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA reference_data GRANT SELECT ON TABLES TO nexa_runtime';
    END IF;
END;
$$;
