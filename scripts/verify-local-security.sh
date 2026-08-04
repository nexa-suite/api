#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
env_file="${api_dir}/.env.local"
if [ ! -f "${env_file}" ]; then
  printf '%s\n' "Missing ${env_file}; run scripts/setup-local-environment.sh first" >&2
  exit 1
fi

set -a
. "${env_file}"
set +a

base_url="${NEXA_LOCAL_API_URL:-http://localhost:8080}"
headers=$(mktemp)
body=$(mktemp)
trap 'rm -f "$headers" "$body"' EXIT HUP INT TERM

assert_status() {
  label="$1"
  expected="$2"
  actual="$3"
  if [ "${actual}" != "${expected}" ]; then
    printf '%s: FAIL (HTTP %s, expected %s)\n' "${label}" "${actual}" "${expected}" >&2
    exit 1
  fi
  printf '%s: PASS\n' "${label}"
}

http_code() {
  curl --silent --show-error --dump-header "${headers}" --output "${body}" --write-out '%{http_code}' --max-time 15 "$@" || true
}

header_check=$(curl --silent --show-error --dump-header "${headers}" --output /dev/null --max-time 15 "${base_url}/actuator/health/readiness")
if [ "${header_check}" != "" ] || ! grep -qi '^X-Content-Type-Options: nosniff' "${headers}" || ! grep -qi '^Content-Security-Policy:' "${headers}" || ! grep -qi '^Referrer-Policy:' "${headers}" || ! grep -qi '^Permissions-Policy:' "${headers}"; then
  printf '%s\n' 'Security response headers: FAIL' >&2
  exit 1
fi
printf '%s\n' 'Security response headers: PASS'

code=$(http_code --header 'Origin: https://evil.example' "${base_url}/actuator/health/readiness")
if grep -qi '^Access-Control-Allow-Origin: https://evil.example' "${headers}"; then
  printf '%s\n' 'CORS origin rejection: FAIL' >&2
  exit 1
fi
printf '%s\n' 'CORS origin rejection: PASS'

code=$(http_code "${base_url}/api/v1/catalog-items?page=0&size=10")
assert_status 'Protected catalog without bearer' '401' "${code}"

code=$(http_code "${base_url}/api/v1/catalog-items/CAT-%27%20OR%201=1")
if [ "${code}" = '500' ]; then
  printf '%s\n' 'SQL/path injection probe: FAIL (HTTP 500)' >&2
  exit 1
fi
printf '%s: PASS (HTTP %s)\n' 'SQL/path injection probe' "${code}"

code=$(http_code --request POST --header 'Content-Type: application/json' --data "{\"identifier\":\"' OR '1'='1\",\"password\":\"invalid\",\"workspaceSlug\":\"icisa\",\"surface\":\"PLATFORM\"}" "${base_url}/api/v1/authentication/sign-in")
if [ "${code}" = '200' ] || [ "${code}" = '500' ]; then
  printf '%s\n' 'Authentication injection probe: FAIL' >&2
  exit 1
fi
printf '%s: PASS (HTTP %s)\n' 'Authentication injection probe' "${code}"

code=$(http_code --request POST --header 'Content-Type: application/json' --data '{"workspaceSlug":"unknown-security-probe"}' "${base_url}/api/v1/auth/workspace-previews")
if [ "${code}" = '200' ] || [ "${code}" = '500' ]; then
  printf '%s\n' 'Workspace enumeration probe: FAIL' >&2
  exit 1
fi
printf '%s: PASS (HTTP %s)\n' 'Workspace enumeration probe' "${code}"

code=$(http_code --request POST --header 'Content-Type: application/json' --form 'subjectType=DELIVERY' --form 'subjectId=00000000-0000-0000-0000-000000000000' --form 'file=@/dev/null;type=application/octet-stream' "${base_url}/api/v1/business-document-evidence")
assert_status 'Evidence upload without bearer' '401' "${code}"

"${script_dir}/verify-local-demo-logins.sh"
