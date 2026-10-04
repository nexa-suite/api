-- Run as nexa_migrator immediately after Flyway, including when Flyway fails.
-- This removes only the temporary V140 policy and leaves FORCE ROW LEVEL SECURITY intact.
BEGIN;

DO $$
DECLARE
    table_owner text;
    row_security boolean;
    forced_rls boolean;
BEGIN
    IF current_user <> 'nexa_migrator' OR session_user <> 'nexa_migrator' THEN
        RAISE EXCEPTION 'V140 cleanup must run directly as nexa_migrator';
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
        RAISE EXCEPTION 'tenant_management.role_definition must remain owned by nexa_migrator with enabled and forced row-level security';
    END IF;
END;
$$;

DO $$
DECLARE
    policy_roles name[];
    policy_command text;
    policy_qual text;
    policy_check text;
    forced_rls boolean;
    row_security boolean;
BEGIN
    SELECT p.roles, p.cmd, p.qual, p.with_check
      INTO policy_roles, policy_command, policy_qual, policy_check
      FROM pg_policies p
     WHERE p.schemaname = 'tenant_management'
       AND p.tablename = 'role_definition'
       AND p.policyname = 'nexa_v140_migrator_insert';

    IF policy_roles IS NOT NULL THEN
        IF array_to_string(policy_roles, ',') IS DISTINCT FROM 'nexa_migrator'
           OR lower(policy_command) IS DISTINCT FROM 'insert'
           OR coalesce(policy_qual, '') <> ''
           OR lower(coalesce(policy_check, '')) NOT LIKE '%current_user%'
           OR lower(coalesce(policy_check, '')) NOT LIKE '%session_user%'
           OR lower(coalesce(policy_check, '')) NOT LIKE '%007b12ab-81dd-307a-ac41-9bc9888924ed%'
           OR lower(coalesce(policy_check, '')) NOT LIKE '%business_operations_manager%'
           OR lower(coalesce(policy_check, '')) NOT LIKE '%system_template%' THEN
            RAISE EXCEPTION 'temporary V140 policy is not the expected exact policy; refusing cleanup';
        END IF;
    END IF;

    DROP POLICY IF EXISTS nexa_v140_migrator_insert ON tenant_management.role_definition;

    SELECT c.relrowsecurity, c.relforcerowsecurity
      INTO row_security, forced_rls
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'tenant_management'
       AND c.relname = 'role_definition'
       AND c.relkind = 'r';

    IF row_security IS DISTINCT FROM TRUE OR forced_rls IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'tenant_management.role_definition must retain enabled and forced row-level security';
    END IF;
END;
$$;

COMMIT;
