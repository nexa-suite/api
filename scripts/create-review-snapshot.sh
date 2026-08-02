#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
repo_name="$(basename "$repo_root")"
snapshot_parent="$(CDPATH= cd -- "${repo_root}/.." && pwd)"
snapshot_dir="${snapshot_parent}/snapshots"
output="${1:-${snapshot_dir}/${repo_name}-review-$(date +%Y%m%d%H%M%S).zip}"

mkdir -p "$(dirname "$output")"
output_parent="$(CDPATH= cd -- "$(dirname "$output")" && pwd)"
output="${output_parent}/$(basename "$output")"

case "$output" in
  "$repo_root"|"$repo_root"/*)
    printf '%s\n' "Snapshot output must be outside the repository" >&2
    exit 2
    ;;
esac

git -C "$repo_root" archive --format=zip --output="$output" HEAD -- \
  . \
  ':(exclude).env.local' \
  ':(exclude).local-keys' \
  ':(exclude).local-keys/**' \
  ':(exclude)**/target' \
  ':(exclude)**/target/**' \
  ':(exclude)**/node_modules' \
  ':(exclude)**/node_modules/**' \
  ':(exclude)**/dist' \
  ':(exclude)**/dist/**' \
  ':(exclude)**/.angular' \
  ':(exclude)**/.angular/**' \
  ':(exclude)**/coverage' \
  ':(exclude)**/coverage/**' \
  ':(exclude)**/playwright-report' \
  ':(exclude)**/playwright-report/**' \
  ':(exclude)**/test-results' \
  ':(exclude)**/test-results/**' \
  ':(exclude)**/.worktrees' \
  ':(exclude)**/.worktrees/**' \
  ':(exclude)**/*.zip'

if unzip -Z1 "$output" | rg -n '(^|/)(\.env\.local|\.local-keys|target|node_modules|dist|\.angular|coverage|playwright-report|test-results|\.worktrees)(/|$)|\.zip$' >/dev/null; then
  printf '%s\n' "Snapshot contains a prohibited path" >&2
  exit 3
fi

printf '%s\n' "$output"
