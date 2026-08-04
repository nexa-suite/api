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
set -a
. "$env_file"
set +a

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
	-e NEXA_RUNTIME_DATABASE_PASSWORD="$NEXA_MODERN_POSTGRES_PASSWORD" \
	modern-postgres sh -eu -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v runtime_password="$NEXA_RUNTIME_DATABASE_PASSWORD" <<'"'"'SQL'"'"'
DO $do$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"'"'nexa_runtime'"'"') THEN
        EXECUTE format('"'"'CREATE ROLE nexa_runtime LOGIN PASSWORD %L'"'"', :'"'"'runtime_password'"'"');
    ELSE
        EXECUTE format('"'"'ALTER ROLE nexa_runtime LOGIN PASSWORD %L'"'"', :'"'"'runtime_password'"'"');
    END IF;
END
$do$;
SQL'

exec docker compose --env-file "$env_file" -f "$compose_file" up --build -d
