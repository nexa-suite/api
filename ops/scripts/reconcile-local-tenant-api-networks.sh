#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

API_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$API_DIRECTORY/.env.local"
TENANT_ROOT="$API_DIRECTORY/.local/tenant-databases"
TENANT_COMPOSE_FILE="$API_DIRECTORY/ops/compose/tenant-business.local.compose.yml"
MODERN_COMPOSE_FILE="$API_DIRECTORY/ops/compose/modern.compose.yml"

fail() { printf '%s\n' "$1" >&2; exit 1; }
private_mode() {
	local path="$1" expected="$2" actual
	[[ ! -L "$path" ]] || return 1
	actual="$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path" 2>/dev/null)" || return 1
	[[ "$actual" == "$expected" ]]
}
read_value() {
	awk -F= -v key="$2" '$1 == key { count++; value=substr($0, index($0, "=") + 1) } END { if (count != 1) exit 1; print value }' "$1"
}
ensure_api_jdbc_url() {
	local properties_file="$1" expected_url="$2" count current temporary
	[[ -f "$properties_file" && ! -L "$properties_file" ]] || fail 'A saved Tenant credential file is missing or unsafe.'
	private_mode "$properties_file" 600 || fail 'A saved Tenant credential file is not owner-only.'
	count="$(awk -F= '$1 == "api-jdbc-url" { count++ } END { print count+0 }' "$properties_file")"
	if [[ "$count" == 1 ]]; then
		current="$(read_value "$properties_file" api-jdbc-url)" || fail 'A saved Tenant API database URL is malformed.'
		[[ "$current" == "$expected_url" ]] || fail 'A saved Tenant API URL differs from its UUID-derived alias.'
		return
	fi
	[[ "$count" == 0 ]] || fail 'A saved Tenant credential file contains duplicate API database URLs.'
	temporary="$properties_file.$$.tmp"
	cat -- "$properties_file" > "$temporary"
	printf 'api-jdbc-url=%s\n' "$expected_url" >> "$temporary"
	chmod 600 "$temporary"
	mv -- "$temporary" "$properties_file"
}

[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail 'The private Modern local environment is unavailable.'
private_mode "$ENV_FILE" 600 || fail 'The Modern local environment must be owner-only.'
[[ ! -L "$API_DIRECTORY/.local" && ! -L "$TENANT_ROOT" ]] || fail 'Local Tenant state cannot be a symbolic link.'
[[ -d "$TENANT_ROOT" ]] || exit 0
private_mode "$TENANT_ROOT" 700 || fail 'Local Tenant state must be owner-only.'

set -a
. "$ENV_FILE"
set +a
docker_context="$(docker context show 2>/dev/null)" || fail 'The local Docker context could not be identified.'
docker_endpoint="$(docker context inspect "$docker_context" --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" \
	|| fail 'The local Docker endpoint could not be verified.'
case "$docker_endpoint" in unix://*|npipe://*) ;; *) fail 'Tenant networking requires a local Docker engine.' ;; esac
export DOCKER_CONTEXT="$docker_context"
docker info >/dev/null 2>&1 || fail 'The local Docker engine is unavailable.'

api_identity="$(docker inspect --format '{{.Id}}|{{.State.Status}}|{{index .Config.Labels "com.docker.compose.service"}}|{{index .Config.Labels "com.docker.compose.project"}}' nexa-modern-api 2>/dev/null)" \
	|| fail 'The local Modern API container could not be identified.'
[[ "$api_identity" =~ ^([0-9a-f]{64})\|running\|modern-api\|nexa-modern$ ]] \
	|| fail 'Tenant network recovery requires the matching running local Modern API container.'
api_container_id="${api_identity%%|*}"

shopt -s nullglob
for tenant_directory in "$TENANT_ROOT"/*; do
	[[ -d "$tenant_directory" && ! -L "$tenant_directory" ]] || continue
	tenant_id="${tenant_directory##*/}"
	[[ "$tenant_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || continue
	private_mode "$tenant_directory" 700 || fail 'A local Tenant database directory is not owner-only.'
	compose_env="$tenant_directory/compose.env"
	state_file="$tenant_directory/state.properties"
	[[ -f "$compose_env" && -f "$state_file" ]] || fail 'Local Tenant network recovery state is incomplete.'
	private_mode "$compose_env" 600 && private_mode "$state_file" 600 \
		|| fail 'Local Tenant network recovery files must be owner-only.'
	[[ "$(read_value "$compose_env" NEXA_TENANT_DATABASE_ID)" == "$tenant_id" \
		&& "$(read_value "$compose_env" NEXA_TENANT_DATABASE_SECRET_DIR)" == "$tenant_directory" \
		&& "$(read_value "$state_file" tenant-id)" == "$tenant_id" ]] \
		|| fail 'Local Tenant network recovery state does not match its Tenant directory.'
	credential_reference="$(read_value "$state_file" credential-secret-reference)" \
		|| fail 'The local Tenant credential reference is malformed.'
	[[ "$credential_reference" =~ ^local-tenant-db:[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] \
		|| fail 'The local Tenant credential reference is not a valid opaque reference.'
	tenant_api_url="jdbc:postgresql://tenant-db-$tenant_id:5432/nexa_tenant_business"
	ensure_api_jdbc_url "$tenant_directory/credentials.properties" "$tenant_api_url"
	[[ "$(read_value "$tenant_directory/credentials.properties" credential-secret-reference)" == "$credential_reference" ]] \
		|| fail 'The local Tenant credential reference differs from its private credential manifest.'
	ensure_api_jdbc_url "$tenant_directory/policy-snapshot-writer.properties" "$tenant_api_url"
	if [[ -e "$tenant_directory/wallet-recharge-worker.properties" ]]; then
		ensure_api_jdbc_url "$tenant_directory/wallet-recharge-worker.properties" "$tenant_api_url"
	fi
	project_name="nexa-tenant-db-$tenant_id"
	network_name="nexa-tenant-business-$tenant_id"
	docker compose --project-name "$project_name" --env-file "$compose_env" -f "$TENANT_COMPOSE_FILE" \
		config --quiet >/dev/null || fail 'A saved Tenant Compose definition is invalid.'
	docker compose --project-name "$project_name" --env-file "$compose_env" -f "$TENANT_COMPOSE_FILE" \
		up -d tenant-database >/dev/null || fail 'A saved Tenant database could not be restored on its private network.'
	network_identity="$(docker network inspect --format '{{.Id}}|{{.Driver}}|{{index .Labels "com.docker.compose.project"}}' \
		"$network_name" 2>/dev/null)" || fail 'A Tenant-private Docker network is missing after restoration.'
	[[ "$network_identity" =~ ^([0-9a-f]{64})\|bridge\|$project_name$ ]] \
		|| fail 'A Tenant-private Docker network has an unexpected identity or owner.'
	expected_network_id="${BASH_REMATCH[1]}"
	attached_network_id="$(docker inspect --format "{{with index .NetworkSettings.Networks \"$network_name\"}}{{.NetworkID}}{{end}}" \
		"$api_container_id" 2>/dev/null)" || fail 'The local API network state could not be inspected.'
	if [[ -z "$attached_network_id" ]]; then
		docker network connect "$network_name" "$api_container_id" \
			|| fail 'The local API could not rejoin a saved Tenant-private network.'
		attached_network_id="$(docker inspect --format "{{with index .NetworkSettings.Networks \"$network_name\"}}{{.NetworkID}}{{end}}" \
			"$api_container_id" 2>/dev/null)" || fail 'The local API Tenant network recovery could not be verified.'
	fi
	[[ "$attached_network_id" == "$expected_network_id" ]] \
		|| fail 'The local API is connected to an unexpected Tenant-private network.'
done
