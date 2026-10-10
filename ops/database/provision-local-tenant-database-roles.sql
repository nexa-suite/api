\set ON_ERROR_STOP on
\getenv migrator_password NEXA_LOCAL_MIGRATOR_PASSWORD
\getenv runtime_password NEXA_LOCAL_RUNTIME_PASSWORD
\getenv policy_snapshot_writer_password NEXA_LOCAL_POLICY_SNAPSHOT_WRITER_PASSWORD

SELECT set_config('nexa.local.migrator_password', :'migrator_password', false) AS _migrator_password \gset
SELECT set_config('nexa.local.runtime_password', :'runtime_password', false) AS _runtime_password \gset
SELECT set_config('nexa.local.policy_snapshot_writer_password', :'policy_snapshot_writer_password', false) AS _policy_snapshot_writer_password \gset
SELECT set_config('nexa.local.wallet_recharge_worker_password', :'wallet_recharge_worker_password', false) AS _wallet_recharge_worker_password \gset
SELECT set_config('nexa.local.business_documents_worker_password', :'business_documents_worker_password', false) AS _business_documents_worker_password \gset
SELECT set_config('nexa.local.payments_worker_password', :'payments_worker_password', false) AS _payments_worker_password \gset
SELECT set_config('nexa.local.business_traceability_worker_password', :'business_traceability_worker_password', false) AS _business_traceability_worker_password \gset

DO $provision$
DECLARE
    bootstrap_role pg_roles%ROWTYPE;
    migration_role pg_roles%ROWTYPE;
    runtime_role pg_roles%ROWTYPE;
    policy_snapshot_writer_role pg_roles%ROWTYPE;
    wallet_recharge_worker_role pg_roles%ROWTYPE;
    business_documents_worker_role pg_roles%ROWTYPE;
    payments_worker_role pg_roles%ROWTYPE;
    business_traceability_worker_role pg_roles%ROWTYPE;
BEGIN
    IF current_user <> 'nexa_tenant_bootstrap_admin' THEN
        RAISE EXCEPTION 'Tenant roles must be provisioned by the isolated bootstrap administrator';
    END IF;

    SELECT * INTO bootstrap_role FROM pg_roles WHERE rolname = 'nexa_tenant_bootstrap_admin';
    IF NOT FOUND OR NOT bootstrap_role.rolsuper THEN
        RAISE EXCEPTION 'Isolated Tenant bootstrap role is not the expected local database administrator';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_database
                   WHERE datname = 'nexa_tenant_business' AND datdba = bootstrap_role.oid) THEN
        RAISE EXCEPTION 'The isolated bootstrap administrator must own the Tenant database';
    END IF;

    SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
    IF FOUND THEN
        IF NOT migration_role.rolcanlogin OR migration_role.rolsuper OR migration_role.rolcreatedb
           OR migration_role.rolcreaterole OR migration_role.rolreplication OR migration_role.rolbypassrls
		   OR EXISTS (SELECT 1 FROM pg_auth_members
		              WHERE member = migration_role.oid OR roleid = migration_role.oid) THEN
            RAISE EXCEPTION 'Existing Tenant migrator role has unsafe privileges or memberships';
        END IF;
        EXECUTE format('ALTER ROLE nexa_migrator PASSWORD %L',
                       current_setting('nexa.local.migrator_password'));
    ELSE
        EXECUTE format(
            'CREATE ROLE nexa_migrator LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            current_setting('nexa.local.migrator_password'));
    END IF;

    SELECT * INTO runtime_role FROM pg_roles WHERE rolname = 'nexa_runtime';
    IF FOUND THEN
        IF NOT runtime_role.rolcanlogin OR runtime_role.rolsuper OR runtime_role.rolcreatedb
           OR runtime_role.rolcreaterole OR runtime_role.rolreplication OR runtime_role.rolbypassrls
		   OR EXISTS (SELECT 1 FROM pg_auth_members
		              WHERE member = runtime_role.oid OR roleid = runtime_role.oid)
		   OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = runtime_role.oid)
		   OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = runtime_role.oid)
		   OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = runtime_role.oid)
		   OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = runtime_role.oid)
		   OR EXISTS (SELECT 1 FROM pg_namespace
		              WHERE has_schema_privilege(runtime_role.oid, oid, 'CREATE')) THEN
            RAISE EXCEPTION 'Existing Tenant runtime role has unsafe privileges or memberships';
        END IF;
        EXECUTE format('ALTER ROLE nexa_runtime PASSWORD %L',
                       current_setting('nexa.local.runtime_password'));
    ELSE
        EXECUTE format(
            'CREATE ROLE nexa_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            current_setting('nexa.local.runtime_password'));
    END IF;

    SELECT * INTO policy_snapshot_writer_role FROM pg_roles WHERE rolname = 'nexa_policy_snapshot_writer';
    IF FOUND THEN
        IF NOT policy_snapshot_writer_role.rolcanlogin OR policy_snapshot_writer_role.rolsuper
           OR policy_snapshot_writer_role.rolcreatedb OR policy_snapshot_writer_role.rolcreaterole
           OR policy_snapshot_writer_role.rolreplication OR policy_snapshot_writer_role.rolbypassrls
           OR EXISTS (SELECT 1 FROM pg_auth_members
                      WHERE member = policy_snapshot_writer_role.oid OR roleid = policy_snapshot_writer_role.oid)
           OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = policy_snapshot_writer_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = policy_snapshot_writer_role.oid)
           OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = policy_snapshot_writer_role.oid)
           OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = policy_snapshot_writer_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace
                      WHERE has_schema_privilege(policy_snapshot_writer_role.oid, oid, 'CREATE')) THEN
            RAISE EXCEPTION 'Existing Tenant policy-snapshot writer has unsafe privileges or memberships';
        END IF;
        EXECUTE format('ALTER ROLE nexa_policy_snapshot_writer PASSWORD %L',
                       current_setting('nexa.local.policy_snapshot_writer_password'));
    ELSE
        EXECUTE format(
            'CREATE ROLE nexa_policy_snapshot_writer LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            current_setting('nexa.local.policy_snapshot_writer_password'));
    END IF;

    SELECT * INTO wallet_recharge_worker_role FROM pg_roles WHERE rolname = 'nexa_wallet_recharge_worker';
    IF FOUND THEN
        IF NOT wallet_recharge_worker_role.rolcanlogin OR wallet_recharge_worker_role.rolsuper
           OR wallet_recharge_worker_role.rolcreatedb OR wallet_recharge_worker_role.rolcreaterole
           OR wallet_recharge_worker_role.rolreplication OR wallet_recharge_worker_role.rolbypassrls
           OR EXISTS (SELECT 1 FROM pg_auth_members
                      WHERE member = wallet_recharge_worker_role.oid OR roleid = wallet_recharge_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = wallet_recharge_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = wallet_recharge_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = wallet_recharge_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = wallet_recharge_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace
                      WHERE has_schema_privilege(wallet_recharge_worker_role.oid, oid, 'CREATE')) THEN
            RAISE EXCEPTION 'Existing Tenant wallet-recharge worker role has unsafe privileges or memberships';
        END IF;
        EXECUTE format('ALTER ROLE nexa_wallet_recharge_worker PASSWORD %L',
                       current_setting('nexa.local.wallet_recharge_worker_password'));
    ELSE
        EXECUTE format(
            'CREATE ROLE nexa_wallet_recharge_worker LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            current_setting('nexa.local.wallet_recharge_worker_password'));
    END IF;

    SELECT * INTO business_documents_worker_role FROM pg_roles WHERE rolname = 'nexa_business_documents_worker';
    IF FOUND THEN
        IF NOT business_documents_worker_role.rolcanlogin OR business_documents_worker_role.rolsuper
           OR business_documents_worker_role.rolcreatedb OR business_documents_worker_role.rolcreaterole
           OR business_documents_worker_role.rolreplication OR business_documents_worker_role.rolbypassrls
           OR EXISTS (SELECT 1 FROM pg_auth_members
                      WHERE member = business_documents_worker_role.oid OR roleid = business_documents_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = business_documents_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = business_documents_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = business_documents_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = business_documents_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace
                      WHERE has_schema_privilege(business_documents_worker_role.oid, oid, 'CREATE')) THEN
            RAISE EXCEPTION 'Existing Tenant Business Documents worker has unsafe privileges or memberships';
        END IF;
        EXECUTE format('ALTER ROLE nexa_business_documents_worker PASSWORD %L',
                       current_setting('nexa.local.business_documents_worker_password'));
    ELSE
        EXECUTE format(
            'CREATE ROLE nexa_business_documents_worker LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            current_setting('nexa.local.business_documents_worker_password'));
    END IF;

    SELECT * INTO payments_worker_role FROM pg_roles WHERE rolname = 'nexa_payments_worker';
    IF FOUND THEN
        IF NOT payments_worker_role.rolcanlogin OR payments_worker_role.rolsuper
           OR payments_worker_role.rolcreatedb OR payments_worker_role.rolcreaterole
           OR payments_worker_role.rolreplication OR payments_worker_role.rolbypassrls
           OR EXISTS (SELECT 1 FROM pg_auth_members
                      WHERE member = payments_worker_role.oid OR roleid = payments_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = payments_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = payments_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = payments_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = payments_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace
                      WHERE has_schema_privilege(payments_worker_role.oid, oid, 'CREATE')) THEN
            RAISE EXCEPTION 'Existing Tenant Payments worker has unsafe privileges or memberships';
        END IF;
        EXECUTE format('ALTER ROLE nexa_payments_worker PASSWORD %L',
                       current_setting('nexa.local.payments_worker_password'));
    ELSE
        EXECUTE format(
            'CREATE ROLE nexa_payments_worker LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            current_setting('nexa.local.payments_worker_password'));
    END IF;

    SELECT * INTO business_traceability_worker_role FROM pg_roles WHERE rolname = 'nexa_business_traceability_worker';
    IF FOUND THEN
        IF NOT business_traceability_worker_role.rolcanlogin OR business_traceability_worker_role.rolsuper
           OR business_traceability_worker_role.rolcreatedb OR business_traceability_worker_role.rolcreaterole
           OR business_traceability_worker_role.rolreplication OR business_traceability_worker_role.rolbypassrls
           OR EXISTS (SELECT 1 FROM pg_auth_members
                      WHERE member = business_traceability_worker_role.oid OR roleid = business_traceability_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_database WHERE datdba = business_traceability_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = business_traceability_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_class WHERE relowner = business_traceability_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner = business_traceability_worker_role.oid)
           OR EXISTS (SELECT 1 FROM pg_namespace
                      WHERE has_schema_privilege(business_traceability_worker_role.oid, oid, 'CREATE')) THEN
            RAISE EXCEPTION 'Existing Tenant traceability worker has unsafe privileges or memberships';
        END IF;
        EXECUTE format('ALTER ROLE nexa_business_traceability_worker PASSWORD %L',
                       current_setting('nexa.local.business_traceability_worker_password'));
    ELSE
        EXECUTE format(
            'CREATE ROLE nexa_business_traceability_worker LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
            current_setting('nexa.local.business_traceability_worker_password'));
    END IF;

END
$provision$;

REVOKE CONNECT, TEMPORARY ON DATABASE nexa_tenant_business FROM PUBLIC;
GRANT CONNECT ON DATABASE nexa_tenant_business TO nexa_migrator, nexa_runtime,
    nexa_policy_snapshot_writer, nexa_wallet_recharge_worker, nexa_business_documents_worker,
    nexa_payments_worker, nexa_business_traceability_worker;
GRANT CREATE ON DATABASE nexa_tenant_business TO nexa_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO nexa_migrator;
