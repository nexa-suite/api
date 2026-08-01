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
printf '%s\n' "COMPANY_OWNER | Platform | ${NEXA_DEV_OWNER_EMAIL} | [redacted]"
printf '%s\n' "SALES         | Platform | ${NEXA_DEV_SALES_EMAIL} | [redacted]"
printf '%s\n' "WAREHOUSE     | Platform | ${NEXA_DEV_WAREHOUSE_EMAIL} | [redacted]"
printf '%s\n' "LOGISTICS     | Platform | ${NEXA_DEV_LOGISTICS_EMAIL} | [redacted]"
printf '%s\n' "BUYER         | Portal   | ${NEXA_DEV_BUYER_EMAIL} | [redacted]"

if [ "${NEXA_SHOW_LOCAL_PASSWORDS:-0}" != "1" ]; then
  printf '%s\n' 'Passwords hidden. Set NEXA_SHOW_LOCAL_PASSWORDS=1 only for an interactive local TTY.'
  exit 0
fi

if [ ! -t 1 ]; then
  printf '%s\n' 'Refusing to print passwords without an interactive TTY.' >&2
  exit 1
fi

printf '%s\n' 'Interactive password output enabled for local development only.'
printf '%s\n' "COMPANY_OWNER password: ${NEXA_DEV_OWNER_PASSWORD}"
printf '%s\n' "SALES password: ${NEXA_DEV_SALES_PASSWORD}"
printf '%s\n' "WAREHOUSE password: ${NEXA_DEV_WAREHOUSE_PASSWORD}"
printf '%s\n' "LOGISTICS password: ${NEXA_DEV_LOGISTICS_PASSWORD}"
printf '%s\n' "BUYER password: ${NEXA_DEV_BUYER_PASSWORD}"
