-- Run as the IAM schema owner or an authorized database administrator after V189.
BEGIN;
DO $$
DECLARE
    owner_id oid;
    account_owner oid;
    migrator pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    current_super boolean;
    current_version integer;
BEGIN
    IF current_user IN ('nexa_migrator', 'nexa_runtime') THEN
        RAISE EXCEPTION 'V190 preparation must run outside application and migration sessions';
    END IF;
    SELECT max(version::integer) INTO current_version FROM public.flyway_schema_history WHERE success;
    IF current_version <> 189 THEN RAISE EXCEPTION 'V190 preparation requires central Flyway version 189'; END IF;
    SELECT nspowner INTO owner_id FROM pg_namespace WHERE nspname='iam';
    SELECT relowner INTO account_owner FROM pg_class WHERE oid=to_regclass('iam.user_account') AND relkind='r';
    SELECT * INTO migrator FROM pg_roles WHERE rolname='nexa_migrator';
    SELECT * INTO runtime_role FROM pg_roles WHERE rolname='nexa_runtime';
    SELECT rolsuper INTO current_super FROM pg_roles WHERE rolname=current_user;
    IF owner_id IS NULL OR account_owner IS DISTINCT FROM owner_id OR migrator.oid IS NULL OR runtime_role.oid IS NULL
       OR owner_id IN (migrator.oid, runtime_role.oid)
       OR NOT migrator.rolcanlogin OR migrator.rolinherit OR migrator.rolsuper OR migrator.rolcreatedb
       OR migrator.rolcreaterole OR migrator.rolreplication OR migrator.rolbypassrls
       OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=migrator.oid OR roleid=migrator.oid)
       OR runtime_role.rolsuper OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole OR runtime_role.rolreplication OR runtime_role.rolbypassrls
       OR pg_has_role(runtime_role.oid, owner_id, 'MEMBER')
       OR has_schema_privilege(migrator.oid, 'iam', 'CREATE')
       OR NOT has_schema_privilege(migrator.oid, 'iam', 'USAGE')
       OR (to_regprocedure('iam.apply_v190_individual_legal_acceptance()') IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE oid=to_regprocedure('iam.apply_v190_individual_legal_acceptance()') AND proowner=owner_id))
       OR to_regclass('iam.legal_acceptance') IS NOT NULL THEN
        RAISE EXCEPTION 'V190 requires isolated IAM ownership and restricted roles (schema owner %, account owner %, migrator IAM usage %, IAM create %)', pg_get_userbyid(owner_id), pg_get_userbyid(account_owner), has_schema_privilege(migrator.oid, 'iam', 'USAGE'), has_schema_privilege(migrator.oid, 'iam', 'CREATE');
    END IF;
    IF NOT current_super AND current_user <> pg_get_userbyid(owner_id) THEN
        RAISE EXCEPTION 'V190 preparation requires the IAM owner or a database administrator';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION iam.apply_v190_individual_legal_acceptance() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog AS $function$
BEGIN
    IF session_user <> 'nexa_migrator' OR current_user = session_user THEN
        RAISE EXCEPTION 'V190 requires a direct restricted migrator session';
    END IF;
    CREATE TABLE iam.legal_acceptance (
        user_id UUID NOT NULL REFERENCES iam.user_account (id),
        document_id VARCHAR(80) NOT NULL,
        document_version VARCHAR(80) NOT NULL,
        content_digest CHAR(64) NOT NULL CHECK (content_digest ~ '^[0-9a-f]{64}$'),
        accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
        PRIMARY KEY (user_id, document_id, document_version)
    );
    REVOKE ALL PRIVILEGES ON iam.legal_acceptance FROM PUBLIC, nexa_runtime;
    GRANT SELECT, INSERT ON iam.legal_acceptance TO nexa_runtime;
    REVOKE ALL ON FUNCTION iam.apply_v190_individual_legal_acceptance() FROM PUBLIC, nexa_migrator;
END;
$function$;
DO $$
DECLARE owner_name text; grantee record;
BEGIN
    SELECT pg_get_userbyid(nspowner) INTO owner_name FROM pg_namespace WHERE nspname='iam';
    EXECUTE format('ALTER FUNCTION iam.apply_v190_individual_legal_acceptance() OWNER TO %I', owner_name);
    FOR grantee IN SELECT DISTINCT a.grantee, r.rolname FROM pg_proc p
        CROSS JOIN LATERAL aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
        LEFT JOIN pg_roles r ON r.oid=a.grantee
        WHERE p.oid='iam.apply_v190_individual_legal_acceptance()'::regprocedure AND a.grantee<>p.proowner
    LOOP
        IF grantee.grantee=0 THEN
            REVOKE ALL ON FUNCTION iam.apply_v190_individual_legal_acceptance() FROM PUBLIC;
        ELSIF grantee.rolname IS NOT NULL THEN
            EXECUTE format('REVOKE ALL ON FUNCTION iam.apply_v190_individual_legal_acceptance() FROM %I', grantee.rolname);
        END IF;
    END LOOP;
    GRANT EXECUTE ON FUNCTION iam.apply_v190_individual_legal_acceptance() TO nexa_migrator;
END $$;
COMMIT;
