#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
env_file="${api_dir}/.env.local"

if [ ! -f "${env_file}" ]; then
  printf '%s\n' "Missing ${env_file}; run scripts/generate-local-env.sh first" >&2
  exit 1
fi

docker compose --env-file "${env_file}" -f "${api_dir}/ops/compose/modern.compose.yml" up -d modern-api
exec "${script_dir}/verify-local-demo-logins.sh"
