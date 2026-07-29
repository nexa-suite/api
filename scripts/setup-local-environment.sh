#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
keys_dir="${api_dir}/.local-keys"
env_file="${api_dir}/.env.local"
modern_up="${api_dir}/ops/compose/scripts/modern-up.sh"

if [ ! -f "${keys_dir}/access-token-private.pem" ] || [ ! -f "${keys_dir}/access-token-public.pem" ]; then
  "${script_dir}/generate-local-signing-keys.sh" >/dev/null
fi

if [ ! -f "$env_file" ]; then
  "${script_dir}/generate-local-env.sh" >/dev/null
fi

"$modern_up"

health_url="http://127.0.0.1:8080/actuator/health"
attempt=1
while [ "$attempt" -le 60 ]; do
  if curl --fail --silent --show-error --max-time 3 "$health_url" >/dev/null 2>&1; then
    break
  fi
  sleep 1
  attempt=$((attempt + 1))
done

if [ "$attempt" -gt 60 ]; then
  printf '%s\n' 'Modern API health did not become ready within 60 seconds' >&2
  exit 1
fi

"${script_dir}/verify-local-access.sh"
printf '%s\n' 'Modern local environment ready: http://localhost:4200, http://localhost:4300, http://localhost:8080'
