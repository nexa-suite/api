#!/usr/bin/env sh
set -eu

compose_file="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/compose.yml"
api_dir="$(CDPATH= cd -- "$(dirname -- "$compose_file")/../.." && pwd)"
env_file="$api_dir/.env.local"
if [ ! -f "$env_file" ]; then
	printf '%s\n' "Missing $env_file; run ./scripts/generate-local-env.sh first" >&2
	exit 1
fi
exec docker compose --env-file "$env_file" -f "$compose_file" --profile modern up --build -d
