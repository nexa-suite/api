#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
api_dir=$(CDPATH= cd -- "${compose_dir}/../.." && pwd)
env_file="${api_dir}/.env.local"
modern_file="${compose_dir}/modern.compose.yml"
legacy_file="${compose_dir}/legacy.compose.yml"

if [ ! -f "$env_file" ]; then
  printf '%s\n' "Missing $env_file; run scripts/setup-local-environment.sh first" >&2
  exit 1
fi

docker compose --env-file "$env_file" -f "$modern_file" down
exec docker compose --env-file "$env_file" -f "$legacy_file" down
