#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
repo_name="$(basename "$repo_root")"
snapshot_parent="$(CDPATH= cd -- "${repo_root}/.." && pwd)"
snapshot_dir="${snapshot_parent}/snapshots"
output="${1:-${snapshot_dir}/${repo_name}-safe-$(date +%Y%m%d%H%M%S).zip}"
commit_sha="$(git -C "$repo_root" rev-parse HEAD)"
branch="$(git -C "$repo_root" symbolic-ref --short -q HEAD || printf '%s' detached)"
created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

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
  ':(exclude).git' \
  ':(exclude).env.local' \
  ':(exclude)**/.env.local' \
  ':(exclude).local-keys' \
  ':(exclude).local-keys/**' \
  ':(exclude)**/.local-keys' \
  ':(exclude)**/.local-keys/**' \
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
  ':(exclude)**/*.zip' \
  ':(exclude)**/.DS_Store' \
  ':(exclude)**/._*' \
  ':(exclude)**/__MACOSX' \
  ':(exclude)**/__MACOSX/**'

manifest_dir="$(mktemp -d "${TMPDIR:-/tmp}/${repo_name}-snapshot.XXXXXX")"
trap 'rm -rf "$manifest_dir"' EXIT
printf '%s\n' \
  "repository=${repo_name}" \
  "commit=${commit_sha}" \
  "branch=${branch}" \
  "createdAt=${created_at}" \
  'exclusionPolicy=.git,.env.local,.local-keys,target,node_modules,dist,.angular,coverage,playwright-report,test-results,.worktrees,ZIP files,.DS_Store,._*,__MACOSX' \
  > "${manifest_dir}/SNAPSHOT-MANIFEST.txt"
(CDPATH= cd -- "$manifest_dir" && zip -q "$output" SNAPSHOT-MANIFEST.txt)

unzip -tq "$output"
if unzip -Z1 "$output" | rg -n '(^|/)(\.git|\.env\.local|\.local-keys|target|node_modules|dist|\.angular|coverage|playwright-report|test-results|\.worktrees|__MACOSX)(/|$)|(^|/)\._|(^|/)\.DS_Store$|\.zip$' >/dev/null; then
  printf '%s\n' "Snapshot contains a prohibited path" >&2
  exit 3
fi
unzip -p "$output" SNAPSHOT-MANIFEST.txt | rg -q "^repository=${repo_name}$"
unzip -p "$output" SNAPSHOT-MANIFEST.txt | rg -q "^commit=${commit_sha}$"

printf '%s\n' "$output"
