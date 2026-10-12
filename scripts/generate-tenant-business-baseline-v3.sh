#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)

if ! docker info >/dev/null 2>&1; then
  printf '%s\n' 'Docker must be available to generate and verify the V3 Tenant baseline.' >&2
  exit 1
fi

cd "${api_dir}"
exec ./mvnw -Dnexa.integration.enabled=true -Dnexa.tenant.baseline.generate=true \
  -Dtest=TenantBusinessDatabaseBaselineIT test
