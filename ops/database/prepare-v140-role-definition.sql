-- Run as nexa_migrator immediately before Flyway applies V140.
-- This policy is intentionally narrow and must be removed by cleanup-v140-role-definition.sql.
BEGIN;

DO $$
DECLARE
    table_owner text;
    row_security boolean;
    forced_rls boolean;
    migrator_login boolean;
    migrator_superuser boolean;
    migrator_bypass_rls boolean;
    migrator_createdb boolean;
    migrator_createrole boolean;
    migrator_replication boolean;
BEGIN
    IF current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V140 preparation must run directly as nexa_migrator';
    END IF;

    SELECT c.relowner::regrole::text, c.relrowsecurity, c.relforcerowsecurity
      INTO table_owner, row_security, forced_rls
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'tenant_management'
       AND c.relname = 'role_definition'
       AND c.relkind = 'r';

    IF table_owner IS DISTINCT FROM 'nexa_migrator'
       OR row_security IS DISTINCT FROM TRUE
       OR forced_rls IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'tenant_management.role_definition must be owned by nexa_migrator with enabled and forced row-level security';
    END IF;

    SELECT r.rolcanlogin, r.rolsuper, r.rolbypassrls, r.rolcreatedb, r.rolcreaterole, r.rolreplication
      INTO migrator_login, migrator_superuser, migrator_bypass_rls,
           migrator_createdb, migrator_createrole, migrator_replication
      FROM pg_roles r
     WHERE r.rolname = 'nexa_migrator';

    IF migrator_login IS DISTINCT FROM TRUE
       OR migrator_superuser IS DISTINCT FROM FALSE
       OR migrator_bypass_rls IS DISTINCT FROM FALSE
       OR migrator_createdb IS DISTINCT FROM FALSE
       OR migrator_createrole IS DISTINCT FROM FALSE
       OR migrator_replication IS DISTINCT FROM FALSE THEN
        RAISE EXCEPTION 'nexa_migrator must remain a login role without database, role, replication, superuser or BYPASSRLS privileges';
    END IF;

    IF pg_has_role('nexa_runtime', 'nexa_migrator', 'USAGE')
       OR pg_has_role('nexa_runtime', 'nexa_migrator', 'SET') THEN
        RAISE EXCEPTION 'nexa_runtime must not inherit or SET ROLE into nexa_migrator';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_policy p
          JOIN pg_class c ON c.oid = p.polrelid
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'tenant_management'
           AND c.relname = 'role_definition'
           AND p.polname = 'nexa_v140_migrator_insert'
    ) THEN
        RAISE EXCEPTION 'nexa_v140_migrator_insert already exists; run cleanup before retrying';
    END IF;
END;
$$;

CREATE POLICY nexa_v140_migrator_insert
    ON tenant_management.role_definition
    FOR INSERT
    TO nexa_migrator
    WITH CHECK (
        current_user = 'nexa_migrator'
        AND session_user = 'nexa_migrator'
        AND id = '007b12ab-81dd-307a-ac41-9bc9888924ed'::uuid
        AND tenant_id IS NULL
        AND workspace_id IS NULL
        AND code = 'business_operations_manager'
        AND name = 'Business operations manager'
        AND description = 'Coordinates operational exceptions without implicit stock or cold-chain authority'
        AND role_type = 'SYSTEM_TEMPLATE'
        AND status = 'ACTIVE'
        AND created_by_membership_id IS NULL
        AND version = 0
        AND created_at IS NOT NULL
        AND updated_at IS NOT NULL
    );

COMMIT;
