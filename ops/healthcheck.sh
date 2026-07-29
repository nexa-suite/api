#!/bin/sh

set -eu

health_url="${NEXA_HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"

exec curl \
    --fail \
    --silent \
    --show-error \
    --max-time "${NEXA_HEALTH_TIMEOUT_SECONDS:-3}" \
    "$health_url" \
    >/dev/null
