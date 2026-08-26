#!/usr/bin/env sh
set -eu

base_url="${NEXA_API_LOCAL_URL:-http://localhost:8080}"
output_file="docs/openapi/openapi.json"

mkdir -p "$(dirname "$output_file")"
curl --fail --silent --show-error --location \
  -H 'X-Forwarded-Host: localhost' \
  -H 'X-Forwarded-Proto: http' \
  "$base_url/v3/api-docs" --output "$output_file"
node -e "JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')); console.log('Validated ' + process.argv[1])" "$output_file"
printf 'Exported %s\n' "$output_file"
