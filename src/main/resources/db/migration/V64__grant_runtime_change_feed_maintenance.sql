-- V64 closes the runtime privilege required by the scheduled append-only
-- change-feed retention adapter. V1-V63 remain immutable.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
        GRANT EXECUTE ON FUNCTION integration.purge_expired_change_events(INTEGER) TO nexa_runtime;
    END IF;
END;
$$;
