#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
WORKSPACE="${NEXA_DEV_WORKSPACE:-${NEXA_LOAD_WORKSPACE:-icisa}}"
REPORT_DIR="${ZAP_REPORT_DIR:-zap-authenticated}"
ZAP_IMAGE="${ZAP_IMAGE:-zaproxy/zap-stable:2.16.1}"

mkdir -p "$REPORT_DIR"
report_dir_abs=$(cd "$REPORT_DIR" && pwd)

required_env=(
  NEXA_DEV_OWNER_EMAIL NEXA_DEV_OWNER_PASSWORD
  NEXA_DEV_SALES_EMAIL NEXA_DEV_SALES_PASSWORD
  NEXA_DEV_WAREHOUSE_EMAIL NEXA_DEV_WAREHOUSE_PASSWORD
  NEXA_DEV_LOGISTICS_EMAIL NEXA_DEV_LOGISTICS_PASSWORD
  NEXA_DEV_BUYER_EMAIL NEXA_DEV_BUYER_PASSWORD
)
for name in "${required_env[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: $name" >&2
    exit 2
  fi
done

spec_file="$REPORT_DIR/authenticated-get-openapi.json"
curl --fail --silent --show-error "$BASE_URL/v3/api-docs" \
  | jq '
      .paths |= with_entries(
        select(.key | contains("{") | not)
        | .value |= with_entries(select(.key == "get"))
        | select(.value | length > 0)
      )
    ' > "$spec_file"

declare -a roles=(
  "tenant-owner|NEXA_DEV_OWNER_EMAIL|NEXA_DEV_OWNER_PASSWORD|PLATFORM"
  "sales|NEXA_DEV_SALES_EMAIL|NEXA_DEV_SALES_PASSWORD|PLATFORM"
  "warehouse|NEXA_DEV_WAREHOUSE_EMAIL|NEXA_DEV_WAREHOUSE_PASSWORD|PLATFORM"
  "logistics|NEXA_DEV_LOGISTICS_EMAIL|NEXA_DEV_LOGISTICS_PASSWORD|PLATFORM"
  "buyer|NEXA_DEV_BUYER_EMAIL|NEXA_DEV_BUYER_PASSWORD|PORTAL"
)

failure=0
for definition in "${roles[@]}"; do
  IFS='|' read -r role email_name password_name surface <<< "$definition"
  email="${!email_name}"
  password="${!password_name}"
  origin_port=4200
  [[ "$surface" == "PORTAL" ]] && origin_port=4300
  login_body=$(jq -nc --arg identifier "$email" --arg secret "$password" --arg workspace "$WORKSPACE" --arg surface "$surface" \
    '{identifier:$identifier,password:$secret,workspaceSlug:$workspace,surface:$surface}')
  login_file="$REPORT_DIR/$role-login.json"
  login_code=$(curl --silent --show-error --output "$login_file" --write-out '%{http_code}' \
    -H 'Content-Type: application/json' -H "Origin: http://localhost:$origin_port" \
    --data "$login_body" "$BASE_URL/api/v1/authentication/sign-in")
  token=$(jq -r '.accessToken // empty' "$login_file")
  if [[ "$login_code" != "200" || -z "$token" ]]; then
    echo "role=$role login_status=$login_code token_present=$([[ -n "$token" ]] && echo true || echo false)" >&2
    failure=1
    continue
  fi
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then
    echo "::add-mask::$token"
  fi

  profile_code=$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    -H "Authorization: Bearer $token" -H "Origin: http://localhost:$origin_port" \
    "$BASE_URL/api/v1/me/profile")
  echo "role=$role login_status=$login_code profile_status=$profile_code"
  if [[ "$profile_code" != "200" ]]; then
    failure=1
    continue
  fi

  log_file="$REPORT_DIR/$role.log"
  report_file="$REPORT_DIR/$role.html"
  json_file="$REPORT_DIR/$role.json"
  # The escaped space is intentional: ZAP must receive a real `Bearer <JWT>` header.
  zap_options="-config replacer.full_list(0).description=nexa-auth -config replacer.full_list(0).enabled=true -config replacer.full_list(0).matchtype=REQ_HEADER -config replacer.full_list(0).matchstr=Authorization -config replacer.full_list(0).regex=false -config replacer.full_list(0).replacement=Bearer\\ $token"
  set +e
  docker run --rm --network host --user 0 \
    -v "$report_dir_abs:/zap/wrk/:rw" \
    "$ZAP_IMAGE" zap-api-scan.py \
      -t /zap/wrk/"$(basename "$spec_file")" -f openapi -O "$BASE_URL" -S -I -m 1 \
      -r "$(basename "$report_file")" -J "$(basename "$json_file")" -z "$zap_options" \
      > "$log_file" 2>&1
  zap_code=$?
  set -e
  if [[ "$zap_code" -ne 0 ]]; then
    echo "role=$role zap_exit=$zap_code" >&2
    failure=1
  fi
  if grep -Eq 'FAIL-NEW:[[:space:]]*[1-9]' "$log_file"; then
    echo "role=$role zap_failures_detected=true" >&2
    failure=1
  fi
  awk '/FAIL-NEW:/{print "role='"$role"' " $0}' "$log_file" | tail -1 || true
done

exit "$failure"
