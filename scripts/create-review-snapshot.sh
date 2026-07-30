#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
repo_name="$(basename "$repo_root")"
output="${1:-${TMPDIR:-/tmp}/${repo_name}-review-$(date +%Y%m%d%H%M%S).zip}"
mkdir -p "$(dirname "$output")"
git -C "$repo_root" archive --format=zip --output="$output" HEAD
printf '%s\n' "$output"
