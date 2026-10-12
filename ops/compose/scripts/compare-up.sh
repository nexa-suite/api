#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
api_dir=$(CDPATH= cd -- "${compose_dir}/../.." && pwd)
env_file="${api_dir}/.env.local"
legacy_file="${compose_dir}/legacy.compose.yml"

if [ ! -f "$env_file" ]; then
  printf '%s\n' "Missing $env_file; run scripts/setup-local-environment.sh first" >&2
  exit 1
fi

"${compose_dir}/scripts/modern-up.sh" fullstack
exec docker compose --env-file "$env_file" -f "$legacy_file" up --build -d
