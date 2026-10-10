#!/usr/bin/env sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
compose_dir="$(CDPATH= cd -- "${script_dir}/.." && pwd)"
api_dir="$(CDPATH= cd -- "${compose_dir}/../.." && pwd)"
compose_file="${compose_dir}/modern.compose.yml"
env_file="${api_dir}/.env.local"
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
	actual="$(stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1" 2>/dev/null)" || return 1
	[ "$actual" = 700 ]
}
private_mode "$tenant_state_root" && private_mode "$tenant_database_root" || {
	printf '%s\n' 'Local Tenant credential directories must be owner-only (0700).' >&2
	exit 1
}
set -a
. "$env_file"
set +a

compose_profile_args=""
case ",${NEXA_MODERN_SPRING_PROFILE:-}," in
	*,observability,*) compose_profile_args="--profile observability" ;;
esac

docker compose --env-file "$env_file" -f "$compose_file" up -d modern-postgres
ready=0
for _ in $(seq 1 60); do
	if docker compose --env-file "$env_file" -f "$compose_file" exec -T modern-postgres pg_isready -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" >/dev/null 2>&1; then
		ready=1
		break
	fi
	sleep 1
done
if [ "$ready" -ne 1 ]; then
	echo "modern-postgres did not become ready" >&2
	exit 1
fi

docker compose --env-file "$env_file" -f "$compose_file" exec -T \
	modern-postgres psql -q -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" -d "$NEXA_MODERN_POSTGRES_DB" \
	-v runtime_password="$NEXA_MODERN_POSTGRES_PASSWORD" \
	-v business_documents_scope_reader_password="$NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD" <<'SQL'
SELECT set_config('nexa.runtime_database_password', :'runtime_password', false) AS _runtime_password \gset
SELECT set_config('nexa.business_documents_scope_reader_password', :'business_documents_scope_reader_password', false) AS _business_documents_scope_reader_password \gset
DO $do$
DECLARE
	scope_reader pg_roles%ROWTYPE;
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexa_runtime') THEN
	    EXECUTE format('CREATE ROLE nexa_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', current_setting('nexa.runtime_database_password'));
	ELSE
	    EXECUTE format('ALTER ROLE nexa_runtime LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', current_setting('nexa.runtime_database_password'));
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
END
$do$;
SQL

docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args up --build -d
docker compose --env-file "$env_file" -f "$compose_file" $compose_profile_args up -d \
	--force-recreate modern-tenant-credentials modern-api

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
nohup env -i PATH="$PATH" "$worker_script" >> "$log_file" 2>&1 </dev/null &
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
