#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
env_file="${api_dir}/.env.local"
if [ ! -f "${env_file}" ]; then
  printf '%s\n' "Missing ${env_file}; run scripts/generate-local-env.sh first" >&2
  exit 1
fi

set -a
. "${env_file}"
set +a

workspace="${NEXA_DEV_WORKSPACE_SLUG:-icisa}"
body=$(mktemp)
trap 'rm -f "$body"' EXIT HUP INT TERM
status=$(curl --silent --show-error --output "$body" --write-out '%{http_code}' --max-time 10 \
  --request POST --header 'Content-Type: application/json' --header 'Origin: http://localhost:4200' \
  --data "{\"workspaceSlug\":\"${workspace}\"}" \
  http://localhost:8080/api/v1/auth/workspace-previews || true)
if [ "$status" != '200' ] || ! grep -q '"recognized"[[:space:]]*:[[:space:]]*true' "$body"; then
  printf '%s\n' "Local workspace preview failed (HTTP ${status})" >&2
  exit 1
fi
printf '%s\n' "Workspace ${workspace}: PASS"
