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

workspace="${NEXA_DEV_WORKSPACE_SLUG}"
api_url="http://localhost:8080/api/v1/authentication/sign-in"

verify_login() {
  label="$1"
  email="$2"
  password="$3"
  surface="$4"
  origin="$5"
  expected="$6"
  body=$(mktemp)
  trap 'rm -f "$body"' EXIT HUP INT TERM
  status=$(curl --silent --show-error --output "$body" --write-out '%{http_code}' \
    --max-time 10 --request POST \
    --header 'Content-Type: application/json' \
    --header "Origin: $origin" \
    --data "{\"identifier\":\"$email\",\"password\":\"$password\",\"workspaceSlug\":\"$workspace\",\"surface\":\"$surface\"}" \
    "$api_url" || true)
  rm -f "$body"
  trap - EXIT HUP INT TERM

  if [ "$expected" = 'success' ] && [ "$status" != '200' ]; then
    printf '%s: FAIL (HTTP %s)\n' "$label" "$status" >&2
    exit 1
  fi
  if [ "$expected" = 'failure' ] && [ "$status" = '200' ]; then
    printf '%s: FAIL (unexpected HTTP 200)\n' "$label" >&2
    exit 1
  fi
  printf '%s: PASS\n' "$label"
}

verify_login 'TENANT_ADMIN+COMPANY_OWNER Platform sign-in' "$NEXA_DEV_OWNER_EMAIL" "$NEXA_DEV_OWNER_PASSWORD" PLATFORM 'http://localhost:4200' success
verify_login 'TENANT_ADMIN-only Platform sign-in' "$NEXA_DEV_TENANT_ADMIN_EMAIL" "$NEXA_DEV_TENANT_ADMIN_PASSWORD" PLATFORM 'http://localhost:4200' success
verify_login 'SALES Platform sign-in' "$NEXA_DEV_SALES_EMAIL" "$NEXA_DEV_SALES_PASSWORD" PLATFORM 'http://localhost:4200' success
verify_login 'WAREHOUSE Platform sign-in' "$NEXA_DEV_WAREHOUSE_EMAIL" "$NEXA_DEV_WAREHOUSE_PASSWORD" PLATFORM 'http://localhost:4200' success
verify_login 'LOGISTICS Platform sign-in' "$NEXA_DEV_LOGISTICS_EMAIL" "$NEXA_DEV_LOGISTICS_PASSWORD" PLATFORM 'http://localhost:4200' success
verify_login 'BUYER Portal sign-in' "$NEXA_DEV_BUYER_EMAIL" "$NEXA_DEV_BUYER_PASSWORD" PORTAL 'http://localhost:4300' success
verify_login 'BUYER Platform denial' "$NEXA_DEV_BUYER_EMAIL" "$NEXA_DEV_BUYER_PASSWORD" PLATFORM 'http://localhost:4200' failure
verify_login 'SALES Portal denial' "$NEXA_DEV_SALES_EMAIL" "$NEXA_DEV_SALES_PASSWORD" PORTAL 'http://localhost:4300' failure
