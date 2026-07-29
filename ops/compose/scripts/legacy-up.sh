#!/usr/bin/env sh
set -eu

compose_file="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)/compose.yml"
exec docker compose -f "$compose_file" --profile legacy up --build -d
