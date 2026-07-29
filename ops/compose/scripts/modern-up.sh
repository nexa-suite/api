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
exec docker compose --env-file "$env_file" -f "$compose_file" up --build -d
