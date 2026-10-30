#!/usr/bin/env sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
compose_dir="$(CDPATH= cd -- "${script_dir}/.." && pwd)"
api_dir="$(CDPATH= cd -- "${compose_dir}/../.." && pwd)"
compose_file="${compose_dir}/modern.compose.yml"
env_file="${api_dir}/.env.local"
requested_mode="${1:-fullstack}"
if [ "$#" -gt 1 ]; then
	printf '%s\n' 'Usage: modern-up.sh [fullstack|api-runtime]' >&2
	exit 2
fi
case "$requested_mode" in
	fullstack|api-runtime) ;;
	*)
		printf '%s\n' 'Usage: modern-up.sh [fullstack|api-runtime]' >&2
		exit 2
		;;
esac
if [ ! -f "$env_file" ]; then
	printf '%s\n' "Missing $env_file; run scripts/setup-local-environment.sh first" >&2
	exit 1
fi

tenant_state_root="$api_dir/.local"
tenant_database_root="$tenant_state_root/tenant-databases"
if [ -L "$tenant_state_root" ] || [ -L "$tenant_database_root" ]; then
	printf '%s\n' 'Local Tenant credential directories cannot be symbolic links.' >&2
	exit 1
fi
if [ ! -d "$tenant_state_root" ]; then
	(umask 077; mkdir -p -- "$tenant_state_root")
fi
if [ ! -d "$tenant_database_root" ]; then
	(umask 077; mkdir -p -- "$tenant_database_root")
fi
private_mode() {
	local actual
	actual="$(stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1" 2>/dev/null)" || return 1
	[ "$actual" = 700 ]
}
private_mode "$tenant_state_root" && private_mode "$tenant_database_root" || {
	printf '%s\n' 'Local Tenant credential directories must be owner-only (0700).' >&2
	exit 1
}
set -a
. "$env_file"
set +a
unset tenant_database_provisioner_password tenant_database_provisioner_password_b64
tenant_database_provisioner_password="${NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD:-}"
unset NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD
if [ -z "${NEXA_DATABASE_RUNTIME_PASSWORD:-}" ] || [ -z "${NEXA_DATABASE_MIGRATOR_PASSWORD:-}" ] \
	|| [ -z "${NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD:-}" ] \
	|| [ -z "${NEXA_WORKSPACE_SCOPE_READER_PASSWORD:-}" ] \
	|| [ -z "${NEXA_INTERNAL_OPERATOR_DATABASE_PASSWORD:-}" ] \
	|| [ -z "$tenant_database_provisioner_password" ]; then
	printf '%s\n' 'The local environment is missing a required runtime, migrator, scope-reader, internal-operator, or Tenant provisioner password; run scripts/generate-local-env.sh, then retry.' >&2
	exit 1
fi

spring_profile="${NEXA_MODERN_SPRING_PROFILE:-local,minio}"
compose_profile_args=""
case ",${spring_profile}," in
	*,observability,*) compose_profile_args="--profile observability" ;;
esac

runtime_password_b64="$(printf '%s' "$NEXA_DATABASE_RUNTIME_PASSWORD" | base64 | tr -d '\r\n')"
migrator_password_b64="$(printf '%s' "$NEXA_DATABASE_MIGRATOR_PASSWORD" | base64 | tr -d '\r\n')"
business_documents_scope_reader_password_b64="$(printf '%s' "$NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD" | base64 | tr -d '\r\n')"
workspace_scope_reader_password_b64="$(printf '%s' "$NEXA_WORKSPACE_SCOPE_READER_PASSWORD" | base64 | tr -d '\r\n')"
internal_operator_database_password_b64="$(printf '%s' "$NEXA_INTERNAL_OPERATOR_DATABASE_PASSWORD" | base64 | tr -d '\r\n')"
tenant_database_provisioner_password_b64="$(printf '%s' "$tenant_database_provisioner_password" | base64 | tr -d '\r\n')"
unset tenant_database_provisioner_password

docker compose --env-file "$env_file" -f "$compose_file" up -d modern-postgres
ready=0
for _ in $(seq 1 60); do
	# The official image starts a socket-only server while initializing a new cluster.
	# Probe TCP so this gate waits for the final server before applying runtime roles.
	if docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres pg_isready -h 127.0.0.1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" >/dev/null 2>&1; then
		ready=1
		break
	fi
	sleep 1
done
if [ "$ready" -ne 1 ]; then
	echo "modern-postgres did not become ready" >&2
	exit 1
fi

{
	printf '\\set runtime_password_b64 %s\n' "$runtime_password_b64"
	printf '\\set migrator_password_b64 %s\n' "$migrator_password_b64"
	printf '\\set business_documents_scope_reader_password_b64 %s\n' "$business_documents_scope_reader_password_b64"
	printf '\\set workspace_scope_reader_password_b64 %s\n' "$workspace_scope_reader_password_b64"
	printf '\\set internal_operator_database_password_b64 %s\n' "$internal_operator_database_password_b64"
	printf '\\set tenant_database_provisioner_password_b64 %s\n' "$tenant_database_provisioner_password_b64"
	cat <<'SQL'
SELECT set_config('nexa.runtime_database_password', convert_from(decode(:'runtime_password_b64', 'base64'), 'UTF8'), false) AS _runtime_password \gset
SELECT set_config('nexa.migrator_password', convert_from(decode(:'migrator_password_b64', 'base64'), 'UTF8'), false) AS _migrator_password \gset
SELECT set_config('nexa.business_documents_scope_reader_password', convert_from(decode(:'business_documents_scope_reader_password_b64', 'base64'), 'UTF8'), false) AS _business_documents_scope_reader_password \gset
SELECT set_config('nexa.workspace_scope_reader_password', convert_from(decode(:'workspace_scope_reader_password_b64', 'base64'), 'UTF8'), false) AS _workspace_scope_reader_password \gset
SELECT set_config('nexa.internal_operator_database_password', convert_from(decode(:'internal_operator_database_password_b64', 'base64'), 'UTF8'), false) AS _internal_operator_database_password \gset
SELECT set_config('nexa.tenant_database_provisioner_password', convert_from(decode(:'tenant_database_provisioner_password_b64', 'base64'), 'UTF8'), false) AS _tenant_database_provisioner_password \gset
DO $do$
DECLARE
	migration_role pg_roles%ROWTYPE;
	runtime_role pg_roles%ROWTYPE;
	scope_reader pg_roles%ROWTYPE;
	workspace_scope_reader pg_roles%ROWTYPE;
	workspace_scope_reader_login pg_roles%ROWTYPE;
	internal_operator_role pg_roles%ROWTYPE;
	internal_operator_login pg_roles%ROWTYPE;
	tenant_database_provisioner pg_roles%ROWTYPE;
BEGIN
	SELECT * INTO migration_role FROM pg_roles WHERE rolname = 'nexa_migrator';
	IF FOUND THEN
		IF migration_role.rolsuper OR migration_role.rolcreatedb OR migration_role.rolcreaterole
			OR migration_role.rolreplication OR migration_role.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=migration_role.oid OR roleid=migration_role.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=migration_role.oid) THEN
			RAISE EXCEPTION 'Existing central migration role has unsafe privileges or memberships';
		END IF;
		EXECUTE format('ALTER ROLE nexa_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.migrator_password'));
	ELSE
		EXECUTE format('CREATE ROLE nexa_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.migrator_password'));
	END IF;

	SELECT * INTO runtime_role FROM pg_roles WHERE rolname = 'nexa_runtime';
	IF FOUND THEN
		IF runtime_role.rolsuper OR runtime_role.rolcreatedb OR runtime_role.rolcreaterole
			OR runtime_role.rolreplication OR runtime_role.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=runtime_role.oid OR roleid=runtime_role.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=runtime_role.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=runtime_role.oid)
			OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=runtime_role.oid)
			OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=runtime_role.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege(runtime_role.oid, oid, 'CREATE')) THEN
			RAISE EXCEPTION 'Existing central runtime role has unsafe privileges or memberships';
		END IF;
		EXECUTE format('ALTER ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.runtime_database_password'));
	ELSE
		EXECUTE format('CREATE ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.runtime_database_password'));
	END IF;

	SELECT * INTO scope_reader FROM pg_roles WHERE rolname = 'nexa_business_documents_scope_reader';
	IF FOUND THEN
		IF NOT scope_reader.rolcanlogin OR scope_reader.rolsuper OR scope_reader.rolcreatedb
			OR scope_reader.rolcreaterole OR scope_reader.rolreplication OR scope_reader.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=scope_reader.oid OR roleid=scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege(scope_reader.oid, oid, 'CREATE')) THEN
			RAISE EXCEPTION 'Existing central Business Documents scope-reader role has unsafe privileges';
		END IF;
		EXECUTE format('ALTER ROLE nexa_business_documents_scope_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.business_documents_scope_reader_password'));
	ELSE
		EXECUTE format('CREATE ROLE nexa_business_documents_scope_reader LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.business_documents_scope_reader_password'));
	END IF;

	SELECT * INTO workspace_scope_reader FROM pg_roles WHERE rolname = 'nexa_workspace_scope_reader';
	SELECT * INTO workspace_scope_reader_login FROM pg_roles WHERE rolname = 'nexa_workspace_scope_reader_login';
	IF workspace_scope_reader.oid IS NOT NULL THEN
		IF workspace_scope_reader.rolcanlogin OR workspace_scope_reader.rolinherit OR workspace_scope_reader.rolsuper
			OR workspace_scope_reader.rolcreatedb OR workspace_scope_reader.rolcreaterole
			OR workspace_scope_reader.rolreplication OR workspace_scope_reader.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=workspace_scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_auth_members
				WHERE roleid=workspace_scope_reader.oid
				  AND member IS DISTINCT FROM workspace_scope_reader_login.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=workspace_scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=workspace_scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=workspace_scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=workspace_scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege(workspace_scope_reader.oid, oid, 'CREATE')) THEN
			RAISE EXCEPTION 'Existing Workspace scope-reader privilege role has unsafe attributes, memberships, or ownership';
		END IF;
	ELSE
		CREATE ROLE nexa_workspace_scope_reader NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
	END IF;

	IF workspace_scope_reader_login.oid IS NOT NULL THEN
		IF NOT workspace_scope_reader_login.rolcanlogin OR workspace_scope_reader_login.rolinherit
			OR workspace_scope_reader_login.rolsuper OR workspace_scope_reader_login.rolcreatedb
			OR workspace_scope_reader_login.rolcreaterole OR workspace_scope_reader_login.rolreplication
			OR workspace_scope_reader_login.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members
				WHERE member=workspace_scope_reader_login.oid
				  AND roleid IS DISTINCT FROM workspace_scope_reader.oid)
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE roleid=workspace_scope_reader_login.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=workspace_scope_reader_login.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=workspace_scope_reader_login.oid)
			OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=workspace_scope_reader_login.oid)
			OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=workspace_scope_reader_login.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege(workspace_scope_reader_login.oid, oid, 'CREATE')) THEN
			RAISE EXCEPTION 'Existing Workspace scope-reader login has unsafe attributes, memberships, or ownership';
		END IF;
		EXECUTE format('ALTER ROLE nexa_workspace_scope_reader_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.workspace_scope_reader_password'));
	ELSE
		EXECUTE format('CREATE ROLE nexa_workspace_scope_reader_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.workspace_scope_reader_password'));
	END IF;
	GRANT nexa_workspace_scope_reader TO nexa_workspace_scope_reader_login WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

	SELECT * INTO internal_operator_role FROM pg_roles WHERE rolname = 'nexa_internal_operator';
	SELECT * INTO internal_operator_login FROM pg_roles WHERE rolname = 'nexa_internal_operator_login';
	IF internal_operator_role.oid IS NOT NULL THEN
		IF internal_operator_role.rolcanlogin OR internal_operator_role.rolinherit OR internal_operator_role.rolsuper
			OR internal_operator_role.rolcreatedb OR internal_operator_role.rolcreaterole
			OR internal_operator_role.rolreplication OR internal_operator_role.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=internal_operator_role.oid)
			OR EXISTS (SELECT 1 FROM pg_auth_members
				WHERE roleid=internal_operator_role.oid
				  AND member IS DISTINCT FROM internal_operator_login.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=internal_operator_role.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=internal_operator_role.oid
				OR has_schema_privilege(internal_operator_role.oid, oid, 'CREATE'))
			OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=internal_operator_role.oid)
			OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=internal_operator_role.oid) THEN
			RAISE EXCEPTION 'Existing internal-operator capability role has unsafe attributes, memberships, or ownership';
		END IF;
	ELSE
		CREATE ROLE nexa_internal_operator NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
	END IF;

	IF internal_operator_login.oid IS NOT NULL THEN
		IF NOT internal_operator_login.rolcanlogin OR internal_operator_login.rolinherit
			OR internal_operator_login.rolsuper OR internal_operator_login.rolcreatedb
			OR internal_operator_login.rolcreaterole OR internal_operator_login.rolreplication
			OR internal_operator_login.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members
				WHERE member=internal_operator_login.oid
				  AND roleid IS DISTINCT FROM internal_operator_role.oid)
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE roleid=internal_operator_login.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=internal_operator_login.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=internal_operator_login.oid
				OR has_schema_privilege(internal_operator_login.oid, oid, 'CREATE'))
			OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=internal_operator_login.oid)
			OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=internal_operator_login.oid) THEN
			RAISE EXCEPTION 'Existing internal-operator login has unsafe attributes, memberships, or ownership';
		END IF;
		EXECUTE format('ALTER ROLE nexa_internal_operator_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.internal_operator_database_password'));
	ELSE
		EXECUTE format('CREATE ROLE nexa_internal_operator_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.internal_operator_database_password'));
	END IF;
	GRANT nexa_internal_operator TO nexa_internal_operator_login WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;

	SELECT * INTO tenant_database_provisioner FROM pg_roles WHERE rolname = 'nexa_tenant_database_provisioner';
	IF FOUND THEN
		IF tenant_database_provisioner.rolsuper OR tenant_database_provisioner.rolcreatedb
			OR tenant_database_provisioner.rolcreaterole OR tenant_database_provisioner.rolreplication
			OR tenant_database_provisioner.rolbypassrls
			OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member=tenant_database_provisioner.oid OR roleid=tenant_database_provisioner.oid)
			OR EXISTS (SELECT 1 FROM pg_database WHERE datdba=tenant_database_provisioner.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner=tenant_database_provisioner.oid)
			OR EXISTS (SELECT 1 FROM pg_class WHERE relowner=tenant_database_provisioner.oid)
			OR EXISTS (SELECT 1 FROM pg_proc WHERE proowner=tenant_database_provisioner.oid)
			OR EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege(tenant_database_provisioner.oid, oid, 'CREATE')) THEN
			RAISE EXCEPTION 'Existing central Tenant database provisioner role has unsafe privileges';
		END IF;
		EXECUTE format('ALTER ROLE nexa_tenant_database_provisioner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.tenant_database_provisioner_password'));
	ELSE
		EXECUTE format('CREATE ROLE nexa_tenant_database_provisioner LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
			current_setting('nexa.tenant_database_provisioner_password'));
	END IF;
END
$do$;
DO $do$
BEGIN
	EXECUTE format('GRANT CONNECT ON DATABASE %I TO nexa_migrator', current_database());
	EXECUTE format('GRANT CONNECT ON DATABASE %I TO nexa_workspace_scope_reader_login', current_database());
	EXECUTE format('GRANT CONNECT ON DATABASE %I TO nexa_internal_operator_login', current_database());
END
$do$;
SQL
} | docker compose --env-file "$env_file" -f "$compose_file" exec -T \
	modern-postgres psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB"

case ",${spring_profile}," in
	*,minio,*|*,s3,*)
		docker compose --env-file "$env_file" -f "$compose_file" up -d modern-minio
		minio_ready=0
		for _ in $(seq 1 120); do
			minio_health="$(docker inspect --format '{{.State.Health.Status}}' nexa-modern-minio 2>/dev/null || true)"
			if [ "$minio_health" = healthy ]; then
				minio_ready=1
				break
			fi
			sleep 1
		done
		if [ "$minio_ready" -ne 1 ]; then
			printf '%s\n' 'modern-minio did not become healthy within 120 seconds.' >&2
			exit 1
		fi
		;;
esac

history_exists="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")"
flyway_version=0
if [ "$history_exists" = t ]; then
	flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
fi

if [ "$flyway_version" -lt 169 ]; then
	# Existing local databases reached V168 with the bootstrap connection. Keep
	# that path bounded, provision the exact V169 ownership/grants as admin, then
	# let the dedicated login apply V169 and any later migrations.
	NEXA_DATABASE_MIGRATOR_USERNAME="$NEXA_MODERN_POSTGRES_USER" \
	NEXA_DATABASE_MIGRATOR_PASSWORD="$NEXA_MODERN_POSTGRES_PASSWORD" \
	NEXA_FLYWAY_TARGET=168 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	bootstrap_ready=0
	for _ in $(seq 1 600); do
		history_exists="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")"
		if [ "$history_exists" = t ]; then
			flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
				psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
				-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
			if [ "$flyway_version" -ge 168 ]; then
				bootstrap_ready=1
				break
			fi
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$bootstrap_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway bootstrap did not reach V168; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v169-tenant-provisioner.sql"
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -lt 171 ]; then
	# Bring the central schema to V171 first. V172 needs a separate narrow DBA
	# ownership transfer before the restricted Flyway login applies it.
	NEXA_FLYWAY_TARGET=171 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	migration_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 171 ]; then
			migration_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$migration_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V171; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
fi

if [ "$flyway_version" -lt 172 ]; then
	# This is idempotent and can run after V171 on databases that already contain
	# it. It does not rerun the V169 worker-grant normalization.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v172-buyer-tracking-view.sql"
fi

if [ "$flyway_version" -lt 173 ]; then
	# Keep nexa_migrator's workspace_membership access column-limited while it
	# applies forced RLS through the fixed, one-shot DDL helper.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v173-workspace-membership-rls.sql"
fi

if [ "$flyway_version" -lt 174 ]; then
	if [ "$flyway_version" -lt 173 ]; then
		# V174 preparation requires a successful V173 history row. Apply V172
		# and V173 with their narrow ownership preparations first.
		NEXA_FLYWAY_TARGET=173 \
			docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
				up --build -d modern-api

		membership_rls_ready=0
		for _ in $(seq 1 600); do
			flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
				psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
				-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
			if [ "$flyway_version" -ge 173 ]; then
				membership_rls_ready=1
				break
			fi
			api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
			if [ "$api_state" = exited ]; then
				break
			fi
			sleep 1
		done
		if [ "$membership_rls_ready" -ne 1 ]; then
			printf '%s\n' 'The local Flyway migration did not reach V173; inspect the modern-api logs before retrying.' >&2
			exit 1
		fi
	fi

	if [ "$flyway_version" -eq 173 ]; then
		# V174 adds the temporary migrator UPDATE grant; the backfill itself
		# runs inside V174 under the existing V173 FORCE RLS policy.
		docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			< "$api_dir/ops/database/prepare-v174-workspace-membership-tenant-scope.sql"
	fi
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -lt 174 ]; then
	# V175 requires V174's Tenant-scoped Membership contract to be current first.
	NEXA_FLYWAY_TARGET=174 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	membership_tenant_scope_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 174 ]; then
			membership_tenant_scope_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$membership_tenant_scope_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V174; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
fi

if [ "$flyway_version" -eq 174 ]; then
	# Keep the Workspace metadata-reader role and its one-shot DDL grants separate
	# from the restricted Flyway login. V175 then verifies the exact role graph.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v175-workspace-scope-reader.sql"
fi

if [ "$flyway_version" -eq 174 ] || [ "$flyway_version" -eq 175 ]; then
	# The restricted migrator does not own the original IAM audit table. Install
	# its one-shot owner-bound ACL helper before V176 revokes runtime reads.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v176-security-audit-runtime-acl.sql"
fi

if [ "$flyway_version" -ge 174 ] && [ "$flyway_version" -lt 177 ]; then
	# V177 applies inbox RLS through a one-shot helper owned by the inbox table
	# owner, because the restricted migrator has no direct integration-schema DDL.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v177-inbox-event-rls.sql"
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -lt 177 ]; then
	NEXA_FLYWAY_TARGET=177 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	inbox_rls_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 177 ]; then
			inbox_rls_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$inbox_rls_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V177; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -eq 177 ]; then
	# V178 changes policies owned by separate governance and IAM roles through
	# fixed one-shot helpers. Install them only after V177 is current.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v178-internal-operator-scope.sql"
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -ge 177 ] && [ "$flyway_version" -lt 179 ]; then
	# V180 changes ACLs on the bootstrap-owned change feed through a one-shot
	# helper, so first let the restricted migrator complete V178 and V179.
	NEXA_FLYWAY_TARGET=179 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	change_feed_acl_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 179 ]; then
			change_feed_acl_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$change_feed_acl_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V179; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -eq 179 ]; then
	# The change_event owner applies the runtime ACL through V180's one-shot helper.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v180-change-event-runtime-acl.sql"
	# IAM support-table owner prepares the runtime ACL for the isolated operator
	# workflow before V181 executes in the same restricted Flyway startup.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v181-internal-support-runtime-acl.sql"
elif [ "$flyway_version" -eq 180 ]; then
	# Recover the case where V180 committed but the V181 helper was not installed
	# or its migration did not complete.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v181-internal-support-runtime-acl.sql"
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -ge 179 ] && [ "$flyway_version" -lt 181 ]; then
	# V182's helper requires a successful V181 history row. Apply V180 and V181
	# under the restricted migrator after their narrowly scoped helpers exist.
	NEXA_FLYWAY_TARGET=181 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	internal_support_acl_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 181 ]; then
			internal_support_acl_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$internal_support_acl_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V181; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -eq 181 ]; then
	# V182 adds profile columns to a bootstrap-owned table through a fixed,
	# owner-bound helper; the application migrator receives EXECUTE only.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v182-organization-settings-profile.sql"

	NEXA_FLYWAY_TARGET=182 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	organization_profile_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 182 ]; then
			organization_profile_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$organization_profile_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V182; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -eq 182 ]; then
	# V183 updates two bootstrap-owned central tables through a single helper.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v183-buyer-account-type-sales-owner-scope.sql"

	# Apply V183 before installing the V184 helper. The restricted migrator may
	# alter these owner-controlled tables only through their one-shot helper.
	NEXA_FLYWAY_TARGET=183 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	buyer_account_scope_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 183 ]; then
			buyer_account_scope_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ]; then
			break
		fi
		sleep 1
	done
	if [ "$buyer_account_scope_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V183; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
fi

flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
	psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
if [ "$flyway_version" -eq 183 ]; then
	# V184 binds Buyer receipt facts to PODs through an owner-bound helper; keep
	# the restricted migrator from directly owning or altering logistics tables.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v184-buyer-acceptance-pod.sql"

	# Apply V184 first; V185's helper must not be installed until the database
	# records V184 as successful.
	NEXA_FLYWAY_TARGET=184 \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	buyer_acceptance_pod_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge 184 ]; then
			buyer_acceptance_pod_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ] || [ "$api_state" = dead ]; then
			break
		fi
		sleep 1
	done
	if [ "$buyer_acceptance_pod_ready" -ne 1 ]; then
		printf '%s\n' 'The local Flyway migration did not reach V184; inspect the modern-api logs before retrying.' >&2
		exit 1
	fi
	flyway_version=184
fi

apply_staged_flyway_migration() {
	target_version="$1"
	migration_label="$2"
	NEXA_FLYWAY_TARGET="$target_version" \
		docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args \
			up --build -d modern-api

	migration_ready=0
	for _ in $(seq 1 600); do
		flyway_version="$(docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
			psql -qAt -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
			-c 'SELECT COALESCE(MAX(version::integer), 0) FROM public.flyway_schema_history WHERE success')"
		if [ "$flyway_version" -ge "$target_version" ]; then
			migration_ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ] || [ "$api_state" = dead ]; then
			break
		fi
		sleep 1
	done
	if [ "$migration_ready" -ne 1 ]; then
		printf 'The local Flyway migration did not reach V%s; inspect the modern-api logs before retrying.\n' \
			"$migration_label" >&2
		exit 1
	fi
	flyway_version="$target_version"
}

if [ "$flyway_version" -eq 184 ]; then
	# V185 validates and links Product Variant family scope through the
	# bootstrap-owned helper; the migrator receives EXECUTE only.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v185-sku-variant-family-scope.sql"
	apply_staged_flyway_migration 185 185
fi

if [ "$flyway_version" -eq 185 ]; then
	# V186 adds one reserved permission through an owner-bound, one-shot helper.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v186-bom-inventory-temperature-audit-read.sql"
	apply_staged_flyway_migration 186 186
fi

if [ "$flyway_version" -eq 186 ]; then
	# V188 links Buyer handoff tokens to the active delivery attempt through an
	# owner-bound, one-shot helper without changing either table's ACL or RLS.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v188-buyer-handoff-to-active-attempt.sql"
	apply_staged_flyway_migration 188 188
fi

if [ "$flyway_version" -eq 188 ]; then
	# V189 aligns central Buyer membership lifecycle and cardinality with the
	# Tenant adapter through a restricted shared-owner DDL helper.
	docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres \
		psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
		< "$api_dir/ops/database/prepare-v189-client-account-buyer-memberships.sql"
fi

if [ "$requested_mode" = "api-runtime" ]; then
	# CI needs the API and its declared runtime dependencies, while the UI build
	# contexts live in sibling repositories that are not part of this checkout.
	docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args up --build -d modern-api
else
	docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args up --build -d
fi
wait_for_modern_api() {
	ready=0
	for _ in $(seq 1 600); do
		api_health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_health" = healthy ]; then
			ready=1
			break
		fi
		api_state="$(docker inspect --format '{{.State.Status}}' nexa-modern-api 2>/dev/null || true)"
		if [ "$api_state" = exited ] || [ "$api_state" = dead ]; then
			break
		fi
		sleep 1
	done
	if [ "$ready" -ne 1 ]; then
		printf '%s\n' 'modern-api did not become healthy; inspect its logs before retrying.' >&2
		exit 1
	fi
}
wait_for_modern_api
docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args up -d \
	--force-recreate modern-tenant-credentials modern-api
wait_for_modern_api

network_reconciler="$api_dir/ops/scripts/reconcile-local-tenant-api-networks.sh"
if [ -x "$network_reconciler" ]; then
	"$network_reconciler"
else
	printf '%s\n' 'Local Tenant API network reconciler is missing or not executable.' >&2
	exit 1
fi

worker_script="$api_dir/ops/scripts/tenant-business-database-provisioning-worker.sh"
worker_state="$api_dir/.local/tenant-provisioning-worker"
pid_file="$worker_state/worker.pid"
ready_file="$worker_state/worker.ready"
log_file="$worker_state/worker.log"
maven_home_directory="$worker_state"
maven_user_home="$worker_state/.m2"
docker_config_directory="$worker_state/docker-config"
if [ -L "$api_dir/.local" ] || [ -L "$worker_state" ]; then
	printf '%s\n' 'Local Tenant provisioning worker state cannot be a symbolic link.' >&2
	exit 1
fi
mkdir -p -- "$worker_state"
chmod 700 "$api_dir/.local" "$worker_state"
if [ -L "$pid_file" ] || [ -L "$ready_file" ] || [ -L "$log_file" ]; then
	printf '%s\n' 'Local Tenant provisioning worker files cannot be symbolic links.' >&2
	exit 1
fi
if [ -f "$pid_file" ] && [ ! -L "$pid_file" ]; then
	worker_pid="$(cat "$pid_file" 2>/dev/null || true)"
	if [ -n "$worker_pid" ] && printf '%s' "$worker_pid" | grep -Eq '^[0-9]+$' && kill -0 "$worker_pid" 2>/dev/null; then
		worker_command="$(ps -p "$worker_pid" -o command= 2>/dev/null || true)"
		case "$worker_command" in
			*"$worker_script"*)
				if ! env -i PATH="$PATH" HOME="$HOME" \
					DOCKER_CONTEXT="${DOCKER_CONTEXT:-}" DOCKER_HOST="${DOCKER_HOST:-}" \
					DOCKER_CONFIG="${DOCKER_CONFIG:-}" DOCKER_TLS_VERIFY="${DOCKER_TLS_VERIFY:-}" \
					DOCKER_CERT_PATH="${DOCKER_CERT_PATH:-}" JAVA_HOME="${JAVA_HOME:-}" \
					XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-}" \
					"$worker_script" --reconcile-schema-manifest; then
					printf '%s\n' 'Tenant schema reconciliation failed; stale Tenant routes remain fail-closed until reconciliation succeeds.' >&2
					exit 1
				fi
				printf '%s\n' 'Local Tenant database provisioning worker is already running.'
				exit 0
				;;
		esac
	fi
fi
rm -f -- "$pid_file"
rm -f -- "$ready_file"
if [ -e "$log_file" ] && [ ! -f "$log_file" ]; then
	printf '%s\n' 'Local Tenant provisioning worker log is not a regular file.' >&2
	exit 1
fi

if [ -n "${DOCKER_CONTEXT:-}" ]; then
	docker_context="$DOCKER_CONTEXT"
	docker_endpoint="$(docker context inspect "$docker_context" --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" || {
		printf '%s\n' 'The selected local Docker context could not be inspected for Tenant provisioning.' >&2
		exit 1
	}
elif [ -n "${DOCKER_HOST:-}" ]; then
	docker_endpoint="$DOCKER_HOST"
else
	docker_context="$(docker context show 2>/dev/null)" || {
		printf '%s\n' 'The local Docker context could not be identified for Tenant provisioning.' >&2
		exit 1
	}
	docker_endpoint="$(docker context inspect "$docker_context" --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" || {
		printf '%s\n' 'The local Docker endpoint could not be inspected for Tenant provisioning.' >&2
		exit 1
	}
fi
case "$docker_endpoint" in
	unix://*|npipe://*) ;;
	*)
		printf '%s\n' 'Tenant provisioning requires a local Docker socket context.' >&2
		exit 1
		;;
esac

if [ -e "$docker_config_directory" ] && [ ! -d "$docker_config_directory" ]; then
	printf '%s\n' 'Local Tenant worker Docker configuration must be a private directory.' >&2
	exit 1
fi
if [ -L "$docker_config_directory" ]; then
	printf '%s\n' 'Local Tenant worker Docker configuration cannot be a symbolic link.' >&2
	exit 1
fi
mkdir -p -- "$docker_config_directory"
chmod 700 "$docker_config_directory"
private_mode "$docker_config_directory" || {
	printf '%s\n' 'Local Tenant worker Docker configuration must be owner-only (0700).' >&2
	exit 1
}
unexpected_docker_config_entry="$(find "$docker_config_directory" -mindepth 1 -maxdepth 1 ! -name cli-plugins -print -quit)"
if [ -n "$unexpected_docker_config_entry" ]; then
	printf '%s\n' 'Local Tenant worker Docker configuration contains unexpected files; refusing to expose Docker credentials.' >&2
	exit 1
fi
docker_plugin_directory="$docker_config_directory/cli-plugins"
docker_plugin_source="${DOCKER_CONFIG:-$HOME/.docker}/cli-plugins/docker-compose"
docker_plugin_target="$docker_plugin_directory/docker-compose"
if [ -x "$docker_plugin_source" ] && [ -f "$docker_plugin_source" ]; then
	if [ -L "$docker_plugin_directory" ] || { [ -e "$docker_plugin_directory" ] && [ ! -d "$docker_plugin_directory" ]; }; then
		printf '%s\n' 'Local Tenant worker Docker CLI plugin directory is unsafe.' >&2
		exit 1
	fi
	mkdir -p -- "$docker_plugin_directory"
	chmod 700 "$docker_plugin_directory"
	if [ -e "$docker_plugin_target" ] || [ -L "$docker_plugin_target" ]; then
		if [ ! -L "$docker_plugin_target" ] || [ "$(readlink "$docker_plugin_target")" != "$docker_plugin_source" ]; then
			printf '%s\n' 'Local Tenant worker Docker Compose plugin has unexpected contents.' >&2
			exit 1
		fi
	else
		ln -s "$docker_plugin_source" "$docker_plugin_target"
	fi
fi

docker_for_worker() {
	env -i PATH="$PATH" HOME="$maven_home_directory" MAVEN_USER_HOME="$maven_user_home" \
		DOCKER_HOST="$docker_endpoint" DOCKER_CONFIG="$docker_config_directory" "$@"
}
if ! docker_for_worker docker info >/dev/null 2>&1 \
	|| ! docker_for_worker docker compose --env-file "$env_file" -f "$compose_file" \
		ps -q modern-postgres >/dev/null 2>&1; then
	printf '%s\n' 'The isolated local Tenant worker Docker/Compose preflight failed; verify the local Docker socket and Compose plugin.' >&2
	exit 1
fi

nohup env -i PATH="$PATH" HOME="$maven_home_directory" MAVEN_USER_HOME="$maven_user_home" \
	DOCKER_HOST="$docker_endpoint" DOCKER_CONFIG="$docker_config_directory" \
	"$worker_script" >> "$log_file" 2>&1 </dev/null &
worker_pid=$!
pid_temporary="$pid_file.$$.tmp"
printf '%s\n' "$worker_pid" > "$pid_temporary"
chmod 600 "$pid_temporary"
mv -- "$pid_temporary" "$pid_file"
chmod 600 "$log_file"
chmod 600 "$pid_file"
worker_ready=0
for _ in $(seq 1 60); do
	if ! kill -0 "$worker_pid" 2>/dev/null; then break; fi
	if [ -f "$ready_file" ] && [ ! -L "$ready_file" ] \
		&& [ "$(cat "$ready_file" 2>/dev/null || true)" = "$worker_pid" ]; then
		worker_ready=1
		break
	fi
	sleep 1
done
if [ "$worker_ready" -ne 1 ]; then
	kill -TERM "$worker_pid" 2>/dev/null || true
	for _ in $(seq 1 10); do kill -0 "$worker_pid" 2>/dev/null || break; sleep 1; done
	rm -f -- "$pid_file" "$ready_file"
	printf '%s\n' 'Local Tenant database provisioning worker did not reach central-queue readiness; inspect the owner-only worker log.' >&2
	exit 1
fi
printf '%s\n' 'Local Tenant database provisioning worker is ready.'
