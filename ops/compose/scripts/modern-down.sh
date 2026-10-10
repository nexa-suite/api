#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
api_dir=$(CDPATH= cd -- "${compose_dir}/../.." && pwd)
env_file="${api_dir}/.env.local"
compose_file="${compose_dir}/modern.compose.yml"

if [ ! -f "$env_file" ]; then
  printf '%s\n' "Missing $env_file; run scripts/setup-local-environment.sh first" >&2
  exit 1
fi

worker_script="$api_dir/ops/scripts/tenant-business-database-provisioning-worker.sh"
worker_state="$api_dir/.local/tenant-provisioning-worker"
pid_file="$worker_state/worker.pid"
if [ -f "$pid_file" ] && [ ! -L "$pid_file" ]; then
	worker_pid="$(cat "$pid_file" 2>/dev/null || true)"
	if [ -n "$worker_pid" ] && printf '%s' "$worker_pid" | grep -Eq '^[0-9]+$' && kill -0 "$worker_pid" 2>/dev/null; then
		worker_command="$(ps -p "$worker_pid" -o command= 2>/dev/null || true)"
		case "$worker_command" in
			*"$worker_script"*)
				kill -TERM "$worker_pid" 2>/dev/null || true
				for _ in $(seq 1 30); do
					kill -0 "$worker_pid" 2>/dev/null || break
					sleep 1
				done
				if kill -0 "$worker_pid" 2>/dev/null; then
					printf '%s\n' 'Local Tenant database worker did not stop; refusing to stop Compose services while it may hold a provisioning task.' >&2
					exit 1
				fi
				;;
		esac
	fi
	rm -f -- "$pid_file"
fi

exec docker compose --env-file "$env_file" -f "$compose_file" down
