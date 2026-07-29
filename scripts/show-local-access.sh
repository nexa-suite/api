#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
env_file="${api_dir}/.env.local"

if [ ! -f "$env_file" ]; then
  printf '%s\n' "Missing $env_file; run scripts/setup-local-environment.sh first" >&2
  exit 1
fi

set -a
. "$env_file"
set +a

printf '%s\n' 'Nexa local-only access'
printf '%s\n' "Workspace: ${NEXA_DEV_WORKSPACE_SLUG}"
printf '%s\n' "COMPANY_OWNER | Platform | ${NEXA_DEV_OWNER_EMAIL} | ${NEXA_DEV_OWNER_PASSWORD}"
printf '%s\n' "SALES         | Platform | ${NEXA_DEV_SALES_EMAIL} | ${NEXA_DEV_SALES_PASSWORD}"
printf '%s\n' "WAREHOUSE     | Platform | ${NEXA_DEV_WAREHOUSE_EMAIL} | ${NEXA_DEV_WAREHOUSE_PASSWORD}"
printf '%s\n' "LOGISTICS     | Platform | ${NEXA_DEV_LOGISTICS_EMAIL} | ${NEXA_DEV_LOGISTICS_PASSWORD}"
printf '%s\n' "BUYER         | Portal   | ${NEXA_DEV_BUYER_EMAIL} | ${NEXA_DEV_BUYER_PASSWORD}"
printf '%s\n' 'Credentials are for local development only. Never commit or log them.'
