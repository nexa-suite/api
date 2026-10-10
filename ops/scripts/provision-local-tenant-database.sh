#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }
usage() {
	printf '%s\n' 'Usage: ops/scripts/provision-local-tenant-database.sh <tenant-uuid> <workspace-uuid> <loopback-port> [--retry-failed] [--task-id <uuid> --claim-token <uuid>] [--workspace-anchor-only --workspace-task-id <uuid> --claim-token <uuid>] [--central-configfile <private-file> --local-state-dir <private-directory>]' >&2
}
private_mode() {
	local path="$1" expected="$2" actual
	[[ ! -L "$path" ]] || return 1
	actual="$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path" 2>/dev/null)" || return 1
	[[ "$actual" == "$expected" ]]
}
read_value() { awk -F= -v key="$2" '$1 == key { count++; value=substr($0, index($0, "=") + 1) } END { if (count != 1) exit 1; print value }' "$1"; }
new_uuid() {
	local value="$(openssl rand -hex 16)"
	printf '%s-%s-%s-%s-%s\n' "${value:0:8}" "${value:8:4}" "${value:12:4}" "${value:16:4}" "${value:20:12}"
}
write_private() { cat > "$1"; chmod 600 "$1"; }
tenant_api_jdbc_url() { printf 'jdbc:postgresql://tenant-db-%s:5432/nexa_tenant_business' "$1"; }
ensure_api_jdbc_url() {
	local properties_file="$1" expected_url="$2" count current temporary
	[[ -f "$properties_file" && ! -L "$properties_file" ]] || fail 'Local Tenant credential file is missing or unsafe.'
	count="$(awk -F= '$1 == "api-jdbc-url" { count++ } END { print count+0 }' "$properties_file")"
	if [[ "$count" == 1 ]]; then
		current="$(read_value "$properties_file" api-jdbc-url)" || fail 'Local Tenant API database URL is malformed.'
		[[ "$current" == "$expected_url" ]] || fail 'Local Tenant API database URL differs from the UUID-bound Tenant network alias.'
		return
	fi
	[[ "$count" == 0 ]] || fail 'Local Tenant credential file contains duplicate API database URLs.'
	temporary="$properties_file.$$.tmp"
	cat -- "$properties_file" > "$temporary"
	printf 'api-jdbc-url=%s\n' "$expected_url" >> "$temporary"
	chmod 600 "$temporary"
	mv -- "$temporary" "$properties_file"
}

binding_failed() {
	local changed
	[[ "$BINDING_ACTIVE" == 1 ]] || return 0
	if changed="$(central_psql <<'SQL'
WITH changed AS (
    UPDATE tenant_management.tenant_business_database_binding
       SET lifecycle_state='FAILED', version=version+1, updated_at=current_timestamp,
           verified_schema_manifest_sha256=NULL
     WHERE tenant_id=:'tenant_id'::UUID AND database_identity=:'database_identity'::UUID
       AND credential_secret_reference=:'secret_reference' AND lifecycle_state='PROVISIONING'
       AND version=:'expected_version'::BIGINT
	       AND (:'task_guard'::BOOLEAN = FALSE OR EXISTS (
	           SELECT 1 FROM tenant_management.tenant_business_database_provisioning_task task
	            WHERE task.task_id=:'task_id'::UUID AND task.tenant_id=:'tenant_id'::UUID
	              AND task.workspace_id=:'workspace_id'::UUID AND task.status='LEASED'
	              AND task.claim_token=:'task_claim_token'::UUID AND task.lease_until>current_timestamp
	       ))
     RETURNING tenant_id
)
SELECT count(*) FROM changed;
SQL
	)" && [[ "$changed" == 1 ]]; then
		printf '%s\n' 'Provisioning failed; the central binding was marked FAILED by version check.' >&2
	else
		printf '%s\n' 'Provisioning failed; the central binding changed concurrently and was not overwritten.' >&2
	fi
}
cleanup() {
	local status=$?
	trap - EXIT
	if [[ "$status" -ne 0 ]]; then binding_failed || true; fi
	if [[ -n "${STAGING_DIRECTORY:-}" && -d "$STAGING_DIRECTORY" ]]; then rm -rf -- "$STAGING_DIRECTORY"; fi
	if [[ -n "${LOCK_DIRECTORY:-}" && -d "$LOCK_DIRECTORY" ]]; then rmdir -- "$LOCK_DIRECTORY" 2>/dev/null || true; fi
	exit "$status"
}

[[ $# -ge 3 ]] || { usage; exit 2; }
TENANT_ID="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
WORKSPACE_ID="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
DATABASE_PORT="$3" RETRY_FAILED=0 WORKSPACE_ANCHOR_ONLY=0
CENTRAL_CONFIG_FILE='' LOCAL_STATE_DIR_ARGUMENT='' PROVISIONING_TASK_ID='' PROVISIONING_CLAIM_TOKEN='' TASK_CLAIM_GUARD=0
WORKSPACE_ANCHOR_TASK_ID='' WORKSPACE_ANCHOR_CLAIM_TOKEN=''
shift 3
while [[ $# -gt 0 ]]; do
	case "$1" in
		--retry-failed)
			[[ "$RETRY_FAILED" == 0 ]] || { usage; exit 2; }
			RETRY_FAILED=1
			shift
			;;
		--central-configfile)
			[[ $# -ge 2 && -z "$CENTRAL_CONFIG_FILE" ]] || { usage; exit 2; }
			CENTRAL_CONFIG_FILE="$2"
			shift 2
			;;
		--local-state-dir)
			[[ $# -ge 2 && -z "$LOCAL_STATE_DIR_ARGUMENT" ]] || { usage; exit 2; }
			LOCAL_STATE_DIR_ARGUMENT="$2"
			shift 2
			;;
		--task-id)
			[[ $# -ge 2 && -z "$PROVISIONING_TASK_ID" ]] || { usage; exit 2; }
			PROVISIONING_TASK_ID="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
			shift 2
			;;
		--workspace-anchor-only)
			[[ "$WORKSPACE_ANCHOR_ONLY" == 0 ]] || { usage; exit 2; }
			WORKSPACE_ANCHOR_ONLY=1
			shift
			;;
		--workspace-task-id)
			[[ $# -ge 2 && -z "$WORKSPACE_ANCHOR_TASK_ID" ]] || { usage; exit 2; }
			WORKSPACE_ANCHOR_TASK_ID="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
			shift 2
			;;
		--claim-token)
			[[ $# -ge 2 && -z "$PROVISIONING_CLAIM_TOKEN" ]] || { usage; exit 2; }
			PROVISIONING_CLAIM_TOKEN="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
			shift 2
			;;
		*) usage; exit 2 ;;
	esac
done
UUID_PATTERN='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
[[ "$TENANT_ID" =~ $UUID_PATTERN && "$WORKSPACE_ID" =~ $UUID_PATTERN ]] || fail 'Tenant and Workspace ids must be UUIDs.'
TENANT_API_JDBC_URL="$(tenant_api_jdbc_url "$TENANT_ID")"
[[ "$DATABASE_PORT" =~ ^[0-9]{1,5}$ ]] && (( DATABASE_PORT >= 1024 && DATABASE_PORT <= 65535 )) || fail 'The loopback port must be from 1024 through 65535.'
if [[ "$WORKSPACE_ANCHOR_ONLY" == 1 ]]; then
	[[ "$WORKSPACE_ANCHOR_TASK_ID" =~ $UUID_PATTERN && "$PROVISIONING_CLAIM_TOKEN" =~ $UUID_PATTERN \
		&& -z "$PROVISIONING_TASK_ID" ]] || fail 'The Workspace anchor task claim is invalid.'
	WORKSPACE_ANCHOR_CLAIM_TOKEN="$PROVISIONING_CLAIM_TOKEN"
	PROVISIONING_TASK_ID='00000000-0000-4000-8000-000000000000'
	PROVISIONING_CLAIM_TOKEN='00000000-0000-4000-8000-000000000000'
	TASK_CLAIM_GUARD=0
elif [[ -n "$PROVISIONING_TASK_ID$PROVISIONING_CLAIM_TOKEN" ]]; then
	[[ "$PROVISIONING_TASK_ID" =~ $UUID_PATTERN && "$PROVISIONING_CLAIM_TOKEN" =~ $UUID_PATTERN ]] \
		|| fail 'The provisioning task claim is invalid.'
	TASK_CLAIM_GUARD=1
else
	PROVISIONING_TASK_ID='00000000-0000-4000-8000-000000000000'
	PROVISIONING_CLAIM_TOKEN='00000000-0000-4000-8000-000000000000'
	[[ -z "$WORKSPACE_ANCHOR_TASK_ID" ]] || fail 'A Workspace anchor task id requires --workspace-anchor-only.'
	WORKSPACE_ANCHOR_CLAIM_TOKEN='00000000-0000-4000-8000-000000000000'
	fi
	if [[ "$WORKSPACE_ANCHOR_ONLY" != 1 ]]; then
	[[ -z "$WORKSPACE_ANCHOR_TASK_ID" ]] || fail 'A Workspace anchor task id requires --workspace-anchor-only.'
fi

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
API_DIRECTORY="$(cd -- "$SCRIPT_DIRECTORY/../.." && pwd)"
cd "$API_DIRECTORY"
CENTRAL_ENV_FILE="$API_DIRECTORY/.env.local"
CENTRAL_COMPOSE_FILE="$API_DIRECTORY/ops/compose/modern.compose.yml"
TENANT_COMPOSE_FILE="$API_DIRECTORY/ops/compose/tenant-business.local.compose.yml"
ROLE_SQL_FILE="$API_DIRECTORY/ops/database/provision-local-tenant-database-roles.sql"
SCOPE_SQL_FILE="$API_DIRECTORY/ops/database/seed-local-tenant-database-scope.sql"
PROJECT_NAME="nexa-tenant-db-$TENANT_ID"
VOLUME_NAME="nexa-tenant-business-$TENANT_ID"
CLI_MAIN='com.nexa.api.bootstrap.runtime.database.tenant.local.LocalTenantBusinessDatabaseProvisioningCli'
BINDING_ACTIVE=0 BINDING_VERSION='' DATABASE_IDENTITY='' SECRET_REFERENCE='' STAGING_DIRECTORY=''
LOCAL_ROOT='' TENANT_DIRECTORY='' VOLUME_MARKER='' LOCK_DIRECTORY=''
CENTRAL_CONTAINER_ID='' SCHEMA_MANIFEST_DIGEST='' WALLET_RECHARGE_SECRET_REFERENCE=''
BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE='' PAYMENT_CALLBACK_SECRET_REFERENCE=''
BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE=''
WORKER_CREDENTIALS_MISSING=0
BUSINESS_DOCUMENTS_WORKER_CREDENTIALS_MISSING=0
PAYMENT_WORKER_CREDENTIALS_MISSING=0
BUSINESS_TRACEABILITY_WORKER_CREDENTIALS_MISSING=0
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || fail 'Docker is required for local Tenant provisioning.'
command -v openssl >/dev/null 2>&1 || fail 'OpenSSL is required to generate local credentials.'
docker_context="$(docker context show 2>/dev/null)" || fail 'The local Docker context could not be identified.'
docker_endpoint="$(docker context inspect "$docker_context" --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" \
	|| fail 'The local Docker endpoint could not be verified.'
case "$docker_endpoint" in
	unix://*|npipe://*) ;;
	*) fail 'Tenant provisioning requires a local Docker socket context.' ;;
esac
export DOCKER_CONTEXT="$docker_context"
docker info >/dev/null 2>&1 || fail 'The local Docker engine is unavailable.'
[[ -f "$TENANT_COMPOSE_FILE" && -f "$ROLE_SQL_FILE" && -f "$SCOPE_SQL_FILE" ]] || fail 'Local Tenant provisioning files are incomplete.'
if [[ -n "$CENTRAL_CONFIG_FILE" ]]; then
	[[ "$CENTRAL_CONFIG_FILE" == /* && -f "$CENTRAL_CONFIG_FILE" && ! -L "$CENTRAL_CONFIG_FILE" ]] \
		|| fail 'The central config file must be an absolute path to a regular private file.'
	private_mode "$CENTRAL_CONFIG_FILE" 600 || fail 'The central config file must be owner-only 0600.'
	central_config_directory="$(dirname -- "$CENTRAL_CONFIG_FILE")"
	private_mode "$central_config_directory" 700 || fail 'The central config directory must be owner-only 0700.'
	awk -F= '
		NF != 2 || $1 != "container-id" || $2 !~ /^[0-9a-f]+$/ { invalid = 1 }
		{ count++ }
		END { exit (invalid || count != 1) }
	' "$CENTRAL_CONFIG_FILE" || fail 'The central config file must contain only one lowercase container-id entry.'
	CENTRAL_CONTAINER_ID="$(read_value "$CENTRAL_CONFIG_FILE" container-id)"
	[[ "$CENTRAL_CONTAINER_ID" =~ ^[0-9a-f]{64}$ ]] || fail 'The central config container id is invalid.'
	central_inspection="$(docker inspect --format '{{.Id}}|{{.State.Status}}|{{.Config.Image}}' "$CENTRAL_CONTAINER_ID" 2>/dev/null)" \
		|| fail 'The central PostgreSQL container could not be inspected in the local Docker context.'
	IFS='|' read -r inspected_id inspected_state inspected_image <<<"$central_inspection"
	[[ "$inspected_id" == "$CENTRAL_CONTAINER_ID" && "$inspected_state" == running ]] \
		|| fail 'The central config must target the matching running local container.'
	case "$inspected_image" in
		postgres:*|docker.io/library/postgres:*|docker.io/postgres:*) ;;
		*) fail 'The central config target must use the official PostgreSQL container image.' ;;
	esac
	docker exec "$CENTRAL_CONTAINER_ID" psql --version >/dev/null 2>&1 \
		|| fail 'The configured PostgreSQL container does not provide psql.'
	if [[ -n "$LOCAL_STATE_DIR_ARGUMENT" ]]; then
		[[ "$LOCAL_STATE_DIR_ARGUMENT" == /* && ! -L "$LOCAL_STATE_DIR_ARGUMENT" ]] \
			|| fail 'The explicit local state directory must be an absolute non-symbolic path.'
		state_parent="$(dirname -- "$LOCAL_STATE_DIR_ARGUMENT")"
		private_mode "$state_parent" 700 || fail 'The explicit local state parent must be owner-only 0700.'
		if [[ -e "$LOCAL_STATE_DIR_ARGUMENT" ]]; then
			private_mode "$LOCAL_STATE_DIR_ARGUMENT" 700 || fail 'The explicit local state directory must be owner-only 0700.'
		else
			mkdir -m 700 -- "$LOCAL_STATE_DIR_ARGUMENT" || fail 'The explicit local state directory could not be created.'
		fi
		LOCAL_ROOT="$(cd -- "$LOCAL_STATE_DIR_ARGUMENT" && pwd -P)"
	else
		fail 'A local state directory is required with an explicit central config file.'
	fi
else
	[[ -f "$CENTRAL_ENV_FILE" && ! -L "$CENTRAL_ENV_FILE" ]] || fail 'Missing private .env.local; prepare the existing Modern local API environment first.'
	private_mode "$CENTRAL_ENV_FILE" 600 || fail 'The central local environment file must be owner-only 0600.'
	[[ -f "$CENTRAL_COMPOSE_FILE" && ! -L "$CENTRAL_COMPOSE_FILE" ]] || fail 'The Modern local PostgreSQL Compose file is missing or unsafe.'
	if [[ -n "$LOCAL_STATE_DIR_ARGUMENT" ]]; then
		fail 'An explicit local state directory requires an explicit central config file.'
	fi
	LOCAL_ROOT="$API_DIRECTORY/.local/tenant-databases"
	[[ ! -L "$API_DIRECTORY/.local" && ! -L "$LOCAL_ROOT" ]] \
		|| fail 'Local Tenant state directories cannot be symbolic links.'
	[[ ! -e "$LOCAL_ROOT" || -d "$LOCAL_ROOT" ]] || fail 'The local Tenant state path is not a directory.'
	mkdir -p -- "$LOCAL_ROOT"
fi
TENANT_DIRECTORY="$LOCAL_ROOT/$TENANT_ID"
VOLUME_MARKER="$TENANT_DIRECTORY/volume-creation-started"
LOCK_DIRECTORY="$LOCAL_ROOT/.locks/$TENANT_ID"
[[ ! -L "$LOCAL_ROOT/.locks" ]] || fail 'Local Tenant lock directory cannot be a symbolic link.'
mkdir -p -- "$LOCAL_ROOT/.locks"
chmod 700 "$LOCAL_ROOT" "$LOCAL_ROOT/.locks"
private_mode "$LOCAL_ROOT" 700 && private_mode "$LOCAL_ROOT/.locks" 700 || fail 'The local Tenant credential root must be owner-only.'
mkdir -- "$LOCK_DIRECTORY" 2>/dev/null || fail 'Another provisioner holds this Tenant lock, or a stale lock needs manual inspection.'
chmod 700 "$LOCK_DIRECTORY"

central_psql() {
	local central_exec
	local central_target
	if [[ -n "$CENTRAL_CONTAINER_ID" ]]; then
		central_exec=(docker exec -i)
		central_target="$CENTRAL_CONTAINER_ID"
	else
		central_exec=(docker compose --env-file "$CENTRAL_ENV_FILE" -f "$CENTRAL_COMPOSE_FILE" exec -T)
		central_target='modern-postgres'
	fi
	"${central_exec[@]}" \
		-e "NEXA_LOCAL_TENANT_ID=$TENANT_ID" -e "NEXA_LOCAL_WORKSPACE_ID=$WORKSPACE_ID" \
		-e "NEXA_LOCAL_DATABASE_IDENTITY=$DATABASE_IDENTITY" -e "NEXA_LOCAL_SECRET_REFERENCE=$SECRET_REFERENCE" \
		-e "NEXA_LOCAL_EXPECTED_VERSION=$BINDING_VERSION" \
		-e "NEXA_LOCAL_SCHEMA_MANIFEST_DIGEST=$SCHEMA_MANIFEST_DIGEST" \
		-e "NEXA_LOCAL_DATABASE_PORT=$DATABASE_PORT" \
		-e "NEXA_LOCAL_WALLET_RECHARGE_SECRET_REFERENCE=$WALLET_RECHARGE_SECRET_REFERENCE" \
		-e "NEXA_LOCAL_PAYMENT_CALLBACK_SECRET_REFERENCE=$PAYMENT_CALLBACK_SECRET_REFERENCE" \
		-e "NEXA_LOCAL_TASK_ID=$PROVISIONING_TASK_ID" \
		-e "NEXA_LOCAL_TASK_CLAIM_TOKEN=$PROVISIONING_CLAIM_TOKEN" \
		-e "NEXA_LOCAL_TASK_GUARD=$TASK_CLAIM_GUARD" \
		-e "NEXA_LOCAL_WORKSPACE_TASK_ID=$WORKSPACE_ANCHOR_TASK_ID" \
		-e "NEXA_LOCAL_WORKSPACE_TASK_CLAIM_TOKEN=$WORKSPACE_ANCHOR_CLAIM_TOKEN" "$central_target" sh -ec '
			exec psql -X -qAt -v ON_ERROR_STOP=1 -v tenant_id="$NEXA_LOCAL_TENANT_ID" \
			-v workspace_id="$NEXA_LOCAL_WORKSPACE_ID" -v database_identity="$NEXA_LOCAL_DATABASE_IDENTITY" \
			-v secret_reference="$NEXA_LOCAL_SECRET_REFERENCE" -v expected_version="$NEXA_LOCAL_EXPECTED_VERSION" \
			-v schema_manifest_digest="$NEXA_LOCAL_SCHEMA_MANIFEST_DIGEST" \
			-v wallet_recharge_secret_reference="$NEXA_LOCAL_WALLET_RECHARGE_SECRET_REFERENCE" \
			-v payment_callback_secret_reference="$NEXA_LOCAL_PAYMENT_CALLBACK_SECRET_REFERENCE" \
			-v task_id="$NEXA_LOCAL_TASK_ID" -v task_claim_token="$NEXA_LOCAL_TASK_CLAIM_TOKEN" \
			-v task_guard="$NEXA_LOCAL_TASK_GUARD" \
			-v expected_database_port="$NEXA_LOCAL_DATABASE_PORT" \
			-v workspace_task_id="${NEXA_LOCAL_WORKSPACE_TASK_ID:-00000000-0000-4000-8000-000000000000}" \
			-v workspace_task_claim_token="${NEXA_LOCAL_WORKSPACE_TASK_CLAIM_TOKEN:-00000000-0000-4000-8000-000000000000}" \
			-U "$POSTGRES_USER" -d "$POSTGRES_DB"
		'
}
tenant_compose() {
	docker compose --project-name "$PROJECT_NAME" --env-file "$TENANT_DIRECTORY/compose.env" \
		-f "$TENANT_COMPOSE_FILE" "$@"
}
TENANT_NETWORK_NAME="nexa-tenant-business-$TENANT_ID"
ensure_api_tenant_network() {
	local api_identity network_identity attached_network_id
	api_identity="$(docker inspect --format '{{.Id}}|{{.State.Status}}|{{index .Config.Labels "com.docker.compose.service"}}|{{index .Config.Labels "com.docker.compose.project"}}' nexa-modern-api 2>/dev/null)" \
		|| fail 'The local Modern API container could not be identified for Tenant network attachment.'
	[[ "$api_identity" =~ ^([0-9a-f]{64})\|running\|modern-api\|nexa-modern$ ]] \
		|| fail 'Tenant provisioning requires the matching running local Modern API container.'
	network_identity="$(docker network inspect --format '{{.Id}}|{{.Driver}}|{{index .Labels "com.docker.compose.project"}}' \
		"$TENANT_NETWORK_NAME" 2>/dev/null)" \
		|| fail 'The Tenant-specific private network could not be inspected.'
	[[ "$network_identity" =~ ^([0-9a-f]{64})\|bridge\|$PROJECT_NAME$ ]] \
		|| fail 'The Tenant network identity or owning Compose project does not match the Tenant UUID.'
	local expected_network_id="${BASH_REMATCH[1]}" api_container_id
	api_container_id="${api_identity%%|*}"
	attached_network_id="$(docker inspect --format "{{with index .NetworkSettings.Networks \"$TENANT_NETWORK_NAME\"}}{{.NetworkID}}{{end}}" \
		"$api_container_id" 2>/dev/null)" || fail 'The local API network attachment could not be inspected.'
	if [[ -z "$attached_network_id" ]]; then
		docker network connect "$TENANT_NETWORK_NAME" "$api_container_id" \
			|| fail 'The local Modern API could not be attached to its Tenant private network.'
		attached_network_id="$(docker inspect --format "{{with index .NetworkSettings.Networks \"$TENANT_NETWORK_NAME\"}}{{.NetworkID}}{{end}}" \
			"$api_container_id" 2>/dev/null)" || fail 'The local API Tenant network attachment could not be verified.'
	fi
	[[ "$attached_network_id" == "$expected_network_id" ]] \
		|| fail 'The local Modern API is attached to an unexpected Tenant network identity.'
}
verify_api_tenant_database_connection() {
	docker run --rm --network "$TENANT_NETWORK_NAME" --read-only \
		--mount "type=bind,src=$TENANT_DIRECTORY/runtime-password,dst=/run/secrets/runtime-password,readonly" \
		--entrypoint /bin/sh postgres:18.4-alpine -ec '
			export PGPASSWORD="$(cat /run/secrets/runtime-password)"
			result="$(psql -X -qAt -v ON_ERROR_STOP=1 -h "$1" -U nexa_runtime -d nexa_tenant_business \
				-c "SELECT 'ready' WHERE (SELECT count(*)=1 AND bool_and(singleton AND tenant_id='$2'::UUID AND database_identity='$3'::UUID) FROM nexa_platform.tenant_business_database_identity) AND EXISTS (SELECT 1 FROM nexa_platform.tenant_workspace_scope_anchor WHERE tenant_id='$2'::UUID AND workspace_id='$4'::UUID)"
			)"
			[[ "$result" == ready ]]
		' sh "tenant-db-$TENANT_ID" "$TENANT_ID" "$DATABASE_IDENTITY" "$WORKSPACE_ID" \
		>/dev/null 2>&1 || fail 'The UUID-bound Tenant network did not pass least-privilege runtime identity and Workspace readiness.'
}
run_cli() {
	local mode="$1"
	NEXA_LOCAL_TENANT_DATABASE_HOME="$TENANT_DIRECTORY" \
	NEXA_LOCAL_TENANT_CREDENTIAL_REFERENCE="$SECRET_REFERENCE" \
		./mvnw -q -Dspring-boot.run.main-class="$CLI_MAIN" \
		"-Dspring-boot.run.arguments=--mode=$mode --tenant-id=$TENANT_ID --workspace-id=$WORKSPACE_ID --database-identity=$DATABASE_IDENTITY" \
		spring-boot:run
}
read_schema_manifest_digest() {
	local digest
	digest="$(run_cli manifest-digest)" || fail 'The current Tenant migration manifest could not be verified.'
	[[ "$digest" =~ ^[0-9a-f]{64}$ ]] || fail 'The current Tenant migration manifest returned invalid evidence.'
	printf '%s\n' "$digest"
}
verify_central_scope() {
	local verified
	verified="$(central_psql <<'SQL'
SELECT 'verified' WHERE EXISTS (
  SELECT 1 FROM tenant_management.tenant tenant
  JOIN tenant_management.workspace workspace ON workspace.tenant_id=tenant.id
  WHERE tenant.id=:'tenant_id'::UUID AND workspace.id=:'workspace_id'::UUID
);
SQL
	)"
	[[ "$verified" == verified ]] || fail 'The central Tenant and Workspace relationship could not be verified.'
}
read_binding() {
	central_psql <<'SQL'
SELECT database_identity::TEXT || '|' || credential_secret_reference || '|' || lifecycle_state || '|' || version::TEXT
FROM tenant_management.tenant_business_database_binding WHERE tenant_id=:'tenant_id'::UUID;
SQL
}
read_wallet_recharge_callback_reference() {
	central_psql <<'SQL'
SELECT coalesce(wallet_recharge_callback_credential_secret_reference, '')
FROM tenant_management.tenant_business_database_binding WHERE tenant_id=:'tenant_id'::UUID;
SQL
}
read_payment_callback_credential_reference() {
	central_psql <<'SQL'
SELECT coalesce(payment_callback_credential_secret_reference, '')
FROM tenant_management.tenant_business_database_binding WHERE tenant_id=:'tenant_id'::UUID;
SQL
}
verify_workspace_anchor_claim() {
	local verified
	verified="$(central_psql <<'SQL'
SET app.current_tenant_id = :'tenant_id';
SET app.cross_scope_workspace_scan = 'true';
SELECT 'verified'
 WHERE EXISTS (
       SELECT 1 FROM tenant_management.workspace workspace
        WHERE workspace.tenant_id=:'tenant_id'::UUID AND workspace.id=:'workspace_id'::UUID
   )
   AND EXISTS (
       SELECT 1 FROM tenant_management.tenant_business_database_binding binding
        WHERE binding.tenant_id=:'tenant_id'::UUID
          AND binding.database_identity=:'database_identity'::UUID
          AND binding.credential_secret_reference=:'secret_reference'
          AND binding.wallet_recharge_callback_credential_secret_reference=:'wallet_recharge_secret_reference'
          AND binding.lifecycle_state='READY'
		  AND binding.verified_schema_manifest_sha256=:'schema_manifest_digest'
   )
   AND EXISTS (
       SELECT 1 FROM tenant_management.tenant_business_database_provisioning_task provisioning
        WHERE provisioning.tenant_id=:'tenant_id'::UUID AND provisioning.status='READY'
          AND provisioning.database_port=:'expected_database_port'::INTEGER
   )
   AND EXISTS (
       SELECT 1 FROM tenant_management.tenant_business_database_workspace_anchor_task anchor
        WHERE anchor.task_id=:'workspace_task_id'::UUID
          AND anchor.tenant_id=:'tenant_id'::UUID AND anchor.workspace_id=:'workspace_id'::UUID
          AND anchor.status='LEASED'
          AND anchor.claim_token=:'workspace_task_claim_token'::UUID
          AND anchor.lease_until>current_timestamp
   );
SQL
	)"
	[[ "$verified" == verified ]] || fail 'The Workspace anchor task, Tenant database binding, or provisioning lease could not be verified.'
}
defer_workspace_anchor_for_schema_upgrade() {
	central_psql <<'SQL'
SET app.current_tenant_id = :'tenant_id';
SET app.cross_scope_workspace_scan = 'true';
WITH binding AS (
    SELECT tenant_id FROM tenant_management.tenant_business_database_binding
     WHERE tenant_id=:'tenant_id'::UUID AND lifecycle_state='READY'
       AND verified_schema_manifest_sha256 IS DISTINCT FROM :'schema_manifest_digest'
), upgrade AS (
    UPDATE tenant_management.tenant_business_database_provisioning_task task
       SET status='PENDING',claim_token=NULL,lease_until=NULL,next_attempt_at=current_timestamp,
           failure_code='SCHEMA_MANIFEST_UPGRADE_REQUIRED',completed_at=NULL,
           updated_at=current_timestamp,version=version+1
      FROM binding
     WHERE task.tenant_id=binding.tenant_id AND task.status='READY'
    RETURNING task.tenant_id
), deferred AS (
    UPDATE tenant_management.tenant_business_database_workspace_anchor_task anchor
       SET status='PENDING',claim_token=NULL,lease_until=NULL,next_attempt_at=current_timestamp + interval '5 seconds',
           failure_code='TENANT_SCHEMA_UPGRADE_REQUIRED',completed_at=NULL,
           updated_at=current_timestamp,version=version+1
     WHERE anchor.task_id=:'workspace_task_id'::UUID
       AND anchor.tenant_id=:'tenant_id'::UUID AND anchor.workspace_id=:'workspace_id'::UUID
       AND anchor.status='LEASED' AND anchor.claim_token=:'workspace_task_claim_token'::UUID
       AND anchor.lease_until>current_timestamp
       AND EXISTS (SELECT 1 FROM binding)
    RETURNING anchor.task_id
)
SELECT task_id::TEXT FROM deferred;
SQL
}
load_existing_state() {
	[[ -d "$TENANT_DIRECTORY" && ! -L "$TENANT_DIRECTORY" ]] || fail 'The central binding has no local private state; refusing to recreate credentials.'
	private_mode "$TENANT_DIRECTORY" 700 || fail 'The local Tenant database directory must be owner-only.'
	local file
	for file in state.properties credentials.properties migrator.properties policy-snapshot-writer.properties \
		bootstrap-admin-password migrator-password runtime-password policy-snapshot-writer-password compose.env; do
		[[ -f "$TENANT_DIRECTORY/$file" ]] && private_mode "$TENANT_DIRECTORY/$file" 600 || fail 'Local Tenant state is incomplete or not owner-only; refusing to regenerate credentials.'
	done
	if [[ -e "$VOLUME_MARKER" || -L "$VOLUME_MARKER" ]]; then
		[[ -f "$VOLUME_MARKER" ]] && private_mode "$VOLUME_MARKER" 600 || fail 'Local Tenant volume history is invalid; inspect it manually.'
	fi
	[[ "$(read_value "$TENANT_DIRECTORY/state.properties" tenant-id)" == "$TENANT_ID" ]] || fail 'Local state belongs to another Tenant.'
	if [[ "$WORKSPACE_ANCHOR_ONLY" != 1 ]]; then
		[[ "$(read_value "$TENANT_DIRECTORY/state.properties" workspace-id)" == "$WORKSPACE_ID" ]] || fail 'Local state belongs to another Workspace.'
	fi
	[[ "$(read_value "$TENANT_DIRECTORY/state.properties" database-port)" == "$DATABASE_PORT" ]] || fail 'A retry must use the original loopback port.'
	[[ "$(read_value "$TENANT_DIRECTORY/compose.env" NEXA_TENANT_DATABASE_PORT)" == "$DATABASE_PORT" ]] || fail 'Local Compose state differs from the saved Tenant port.'
	[[ "$(read_value "$TENANT_DIRECTORY/compose.env" NEXA_TENANT_DATABASE_ID)" == "$TENANT_ID" ]] || fail 'Local Compose state differs from the central Tenant id.'
	[[ "$(read_value "$TENANT_DIRECTORY/compose.env" NEXA_TENANT_DATABASE_SECRET_DIR)" == "$TENANT_DIRECTORY" ]] || fail 'Local Compose secrets must stay in the matching private Tenant directory.'
	DATABASE_IDENTITY="$(read_value "$TENANT_DIRECTORY/state.properties" database-identity)"
	SECRET_REFERENCE="$(read_value "$TENANT_DIRECTORY/state.properties" credential-secret-reference)"
	[[ "$DATABASE_IDENTITY" =~ $UUID_PATTERN ]] || fail 'Local database identity is invalid.'
	[[ "$SECRET_REFERENCE" =~ ^local-tenant-db:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail 'Local credential reference is invalid.'
	[[ "$(read_value "$TENANT_DIRECTORY/credentials.properties" credential-secret-reference)" == "$SECRET_REFERENCE" ]] || fail 'Local credentials differ from the private binding reference.'
	[[ "$(read_value "$TENANT_DIRECTORY/credentials.properties" username)" == nexa_runtime ]] || fail 'Local Tenant credentials must use the runtime role.'
	[[ "$(read_value "$TENANT_DIRECTORY/credentials.properties" jdbc-url)" == "jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business" ]] || fail 'Local Tenant runtime credentials target an unexpected database.'
	TENANT_API_JDBC_URL="$(tenant_api_jdbc_url "$TENANT_ID")"
	ensure_api_jdbc_url "$TENANT_DIRECTORY/credentials.properties" "$TENANT_API_JDBC_URL"
	[[ "$(read_value "$TENANT_DIRECTORY/migrator.properties" username)" == nexa_migrator ]] || fail 'Local Tenant migrations must use the dedicated migrator role.'
	[[ "$(read_value "$TENANT_DIRECTORY/migrator.properties" jdbc-url)" == "jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business" ]] || fail 'Local Tenant migration credentials target an unexpected database.'
	[[ "$(read_value "$TENANT_DIRECTORY/policy-snapshot-writer.properties" username)" == nexa_policy_snapshot_writer ]] || fail 'Local policy snapshots must use the dedicated writer role.'
	[[ "$(read_value "$TENANT_DIRECTORY/policy-snapshot-writer.properties" jdbc-url)" == "jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business" ]] || fail 'Local policy-snapshot writer credentials target an unexpected database.'
	ensure_api_jdbc_url "$TENANT_DIRECTORY/policy-snapshot-writer.properties" "$TENANT_API_JDBC_URL"
	WALLET_RECHARGE_SECRET_REFERENCE="local-tenant-wallet-recharge-worker:$TENANT_ID"
	BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE="local-tenant-business-documents-worker:$TENANT_ID"
	PAYMENT_CALLBACK_SECRET_REFERENCE="local-tenant-payment-callback-worker:$TENANT_ID"
	BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE="local-tenant-business-traceability-worker:$TENANT_ID"
	if [[ -e "$TENANT_DIRECTORY/wallet-recharge-worker.properties" || -e "$TENANT_DIRECTORY/wallet-recharge-worker-password" ]]; then
		[[ -f "$TENANT_DIRECTORY/wallet-recharge-worker.properties" && -f "$TENANT_DIRECTORY/wallet-recharge-worker-password" ]] \
			&& private_mode "$TENANT_DIRECTORY/wallet-recharge-worker.properties" 600 \
			&& private_mode "$TENANT_DIRECTORY/wallet-recharge-worker-password" 600 \
			|| fail 'Local Tenant wallet worker state is incomplete or not owner-only; refusing to regenerate credentials.'
		[[ "$(read_value "$TENANT_DIRECTORY/wallet-recharge-worker.properties" credential-secret-reference)" == "$WALLET_RECHARGE_SECRET_REFERENCE" ]] \
			|| fail 'Local Tenant wallet worker reference does not match the Tenant.'
		[[ "$(read_value "$TENANT_DIRECTORY/wallet-recharge-worker.properties" username)" == nexa_wallet_recharge_worker ]] \
			|| fail 'Local Tenant wallet worker credentials require the dedicated role.'
		[[ "$(read_value "$TENANT_DIRECTORY/wallet-recharge-worker.properties" jdbc-url)" == "jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business" ]] \
			|| fail 'Local Tenant wallet worker credentials target an unexpected database.'
		ensure_api_jdbc_url "$TENANT_DIRECTORY/wallet-recharge-worker.properties" "$TENANT_API_JDBC_URL"
		WORKER_CREDENTIALS_MISSING=0
	else
		if grep -q '^wallet-recharge-callback-credential-reference=' "$TENANT_DIRECTORY/state.properties"; then
			fail 'Local Tenant state references a wallet worker credential that is missing; refusing to regenerate it.'
		fi
		WORKER_CREDENTIALS_MISSING=1
	fi
	if [[ -e "$TENANT_DIRECTORY/business-documents-worker.properties" || -e "$TENANT_DIRECTORY/business-documents-worker-password" ]]; then
		[[ -f "$TENANT_DIRECTORY/business-documents-worker.properties" && -f "$TENANT_DIRECTORY/business-documents-worker-password" ]] \
			&& private_mode "$TENANT_DIRECTORY/business-documents-worker.properties" 600 \
			&& private_mode "$TENANT_DIRECTORY/business-documents-worker-password" 600 \
			|| fail 'Local Tenant Business Documents worker state is incomplete or not owner-only; refusing to regenerate credentials.'
		[[ "$(read_value "$TENANT_DIRECTORY/business-documents-worker.properties" credential-secret-reference)" == "$BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE" ]] \
			|| fail 'Local Tenant Business Documents worker reference does not match the Tenant.'
		[[ "$(read_value "$TENANT_DIRECTORY/business-documents-worker.properties" username)" == nexa_business_documents_worker ]] \
			|| fail 'Local Business Documents worker credentials require the dedicated role.'
		[[ "$(read_value "$TENANT_DIRECTORY/business-documents-worker.properties" jdbc-url)" == "jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business" ]] \
			|| fail 'Local Business Documents worker credentials target an unexpected database.'
		ensure_api_jdbc_url "$TENANT_DIRECTORY/business-documents-worker.properties" "$TENANT_API_JDBC_URL"
		BUSINESS_DOCUMENTS_WORKER_CREDENTIALS_MISSING=0
	else
		BUSINESS_DOCUMENTS_WORKER_CREDENTIALS_MISSING=1
	fi
	if [[ -e "$TENANT_DIRECTORY/payment-callback-worker.properties" || -e "$TENANT_DIRECTORY/payment-callback-worker-password" ]]; then
		[[ -f "$TENANT_DIRECTORY/payment-callback-worker.properties" && -f "$TENANT_DIRECTORY/payment-callback-worker-password" ]] \
			&& private_mode "$TENANT_DIRECTORY/payment-callback-worker.properties" 600 \
			&& private_mode "$TENANT_DIRECTORY/payment-callback-worker-password" 600 \
			|| fail 'Local Tenant Payments worker state is incomplete or not owner-only; refusing to regenerate credentials.'
		[[ "$(read_value "$TENANT_DIRECTORY/payment-callback-worker.properties" credential-secret-reference)" == "$PAYMENT_CALLBACK_SECRET_REFERENCE" ]] \
			|| fail 'Local Tenant Payments worker reference does not match the Tenant.'
		[[ "$(read_value "$TENANT_DIRECTORY/payment-callback-worker.properties" username)" == nexa_payments_worker ]] \
			|| fail 'Local Tenant Payments credentials require the dedicated worker role.'
		[[ "$(read_value "$TENANT_DIRECTORY/payment-callback-worker.properties" jdbc-url)" == "jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business" ]] \
			|| fail 'Local Tenant Payments worker credentials target an unexpected database.'
		ensure_api_jdbc_url "$TENANT_DIRECTORY/payment-callback-worker.properties" "$TENANT_API_JDBC_URL"
		PAYMENT_WORKER_CREDENTIALS_MISSING=0
	else
		if grep -q '^payment-callback-worker-credential-reference=' "$TENANT_DIRECTORY/state.properties"; then
			fail 'Local Tenant state references Payments worker credentials that are missing; refusing to regenerate them.'
		fi
		PAYMENT_WORKER_CREDENTIALS_MISSING=1
	fi
	if [[ -e "$TENANT_DIRECTORY/business-traceability-worker.properties" || -e "$TENANT_DIRECTORY/business-traceability-worker-password" ]]; then
		[[ -f "$TENANT_DIRECTORY/business-traceability-worker.properties" && -f "$TENANT_DIRECTORY/business-traceability-worker-password" ]] \
			&& private_mode "$TENANT_DIRECTORY/business-traceability-worker.properties" 600 \
			&& private_mode "$TENANT_DIRECTORY/business-traceability-worker-password" 600 \
			|| fail 'Local Tenant traceability worker state is incomplete or not owner-only; refusing to regenerate credentials.'
		[[ "$(read_value "$TENANT_DIRECTORY/business-traceability-worker.properties" credential-secret-reference)" == "$BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE" ]] \
			|| fail 'Local Tenant traceability worker reference does not match the Tenant.'
		[[ "$(read_value "$TENANT_DIRECTORY/business-traceability-worker.properties" username)" == nexa_business_traceability_worker ]] \
			|| fail 'Local Tenant traceability credentials require the dedicated worker role.'
		[[ "$(read_value "$TENANT_DIRECTORY/business-traceability-worker.properties" jdbc-url)" == "jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business" ]] \
			|| fail 'Local Tenant traceability worker credentials target an unexpected database.'
		ensure_api_jdbc_url "$TENANT_DIRECTORY/business-traceability-worker.properties" "$TENANT_API_JDBC_URL"
		BUSINESS_TRACEABILITY_WORKER_CREDENTIALS_MISSING=0
	else
		if grep -q '^business-traceability-worker-credential-reference=' "$TENANT_DIRECTORY/state.properties"; then
			fail 'Local Tenant state references traceability worker credentials that are missing; refusing to regenerate them.'
		fi
		BUSINESS_TRACEABILITY_WORKER_CREDENTIALS_MISSING=1
	fi
}
create_local_state() {
	local bootstrap_password migrator_password runtime_password policy_snapshot_writer_password
	local wallet_recharge_worker_password business_documents_worker_password payment_callback_worker_password
	local business_traceability_worker_password
	bootstrap_password="$(openssl rand -hex 32)" migrator_password="$(openssl rand -hex 32)" \
		runtime_password="$(openssl rand -hex 32)" policy_snapshot_writer_password="$(openssl rand -hex 32)" \
		wallet_recharge_worker_password="$(openssl rand -hex 32)" \
		business_documents_worker_password="$(openssl rand -hex 32)" \
		payment_callback_worker_password="$(openssl rand -hex 32)" \
		business_traceability_worker_password="$(openssl rand -hex 32)"
	DATABASE_IDENTITY="$(new_uuid)" SECRET_REFERENCE="local-tenant-db:$(new_uuid)"
	WALLET_RECHARGE_SECRET_REFERENCE="local-tenant-wallet-recharge-worker:$TENANT_ID"
	BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE="local-tenant-business-documents-worker:$TENANT_ID"
	PAYMENT_CALLBACK_SECRET_REFERENCE="local-tenant-payment-callback-worker:$TENANT_ID"
	BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE="local-tenant-business-traceability-worker:$TENANT_ID"
	STAGING_DIRECTORY="$(mktemp -d "$LOCAL_ROOT/.${TENANT_ID}.setup.XXXXXX")"
	chmod 700 "$STAGING_DIRECTORY"
	write_private "$STAGING_DIRECTORY/bootstrap-admin-password" <<<"$bootstrap_password"
	write_private "$STAGING_DIRECTORY/migrator-password" <<<"$migrator_password"
	write_private "$STAGING_DIRECTORY/runtime-password" <<<"$runtime_password"
	write_private "$STAGING_DIRECTORY/policy-snapshot-writer-password" <<<"$policy_snapshot_writer_password"
	write_private "$STAGING_DIRECTORY/wallet-recharge-worker-password" <<<"$wallet_recharge_worker_password"
	write_private "$STAGING_DIRECTORY/business-documents-worker-password" <<<"$business_documents_worker_password"
	write_private "$STAGING_DIRECTORY/payment-callback-worker-password" <<<"$payment_callback_worker_password"
	write_private "$STAGING_DIRECTORY/business-traceability-worker-password" <<<"$business_traceability_worker_password"
	write_private "$STAGING_DIRECTORY/migrator.properties" <<EOF
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
username=nexa_migrator
password=$migrator_password
EOF
	write_private "$STAGING_DIRECTORY/policy-snapshot-writer.properties" <<EOF
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_policy_snapshot_writer
password=$policy_snapshot_writer_password
EOF
	write_private "$STAGING_DIRECTORY/wallet-recharge-worker.properties" <<EOF
credential-secret-reference=$WALLET_RECHARGE_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_wallet_recharge_worker
password=$wallet_recharge_worker_password
EOF
	write_private "$STAGING_DIRECTORY/business-documents-worker.properties" <<EOF
credential-secret-reference=$BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_business_documents_worker
password=$business_documents_worker_password
EOF
	write_private "$STAGING_DIRECTORY/payment-callback-worker.properties" <<EOF
credential-secret-reference=$PAYMENT_CALLBACK_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_payments_worker
password=$payment_callback_worker_password
EOF
	write_private "$STAGING_DIRECTORY/business-traceability-worker.properties" <<EOF
credential-secret-reference=$BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_business_traceability_worker
password=$business_traceability_worker_password
EOF
	write_private "$STAGING_DIRECTORY/credentials.properties" <<EOF
credential-secret-reference=$SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_runtime
password=$runtime_password
EOF
	write_private "$STAGING_DIRECTORY/state.properties" <<EOF
tenant-id=$TENANT_ID
workspace-id=$WORKSPACE_ID
database-identity=$DATABASE_IDENTITY
credential-secret-reference=$SECRET_REFERENCE
database-port=$DATABASE_PORT
wallet-recharge-callback-credential-reference=$WALLET_RECHARGE_SECRET_REFERENCE
business-documents-worker-credential-reference=$BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE
payment-callback-worker-credential-reference=$PAYMENT_CALLBACK_SECRET_REFERENCE
business-traceability-worker-credential-reference=$BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE
EOF
	write_private "$STAGING_DIRECTORY/compose.env" <<EOF
NEXA_TENANT_DATABASE_PORT=$DATABASE_PORT
NEXA_TENANT_DATABASE_ID=$TENANT_ID
NEXA_TENANT_DATABASE_SECRET_DIR=$TENANT_DIRECTORY
EOF
	unset bootstrap_password migrator_password runtime_password policy_snapshot_writer_password \
		wallet_recharge_worker_password business_documents_worker_password payment_callback_worker_password \
		business_traceability_worker_password
	[[ ! -e "$TENANT_DIRECTORY" && ! -L "$TENANT_DIRECTORY" ]] || fail 'Local Tenant state appeared concurrently; inspect it before retrying.'
	mv -- "$STAGING_DIRECTORY" "$TENANT_DIRECTORY"
	STAGING_DIRECTORY=''
	WORKER_CREDENTIALS_MISSING=0
	BUSINESS_DOCUMENTS_WORKER_CREDENTIALS_MISSING=0
	PAYMENT_WORKER_CREDENTIALS_MISSING=0
	BUSINESS_TRACEABILITY_WORKER_CREDENTIALS_MISSING=0
}

ensure_wallet_recharge_worker_credentials() {
	local role_exists
	WALLET_RECHARGE_SECRET_REFERENCE="local-tenant-wallet-recharge-worker:$TENANT_ID"
	if [[ "$WORKER_CREDENTIALS_MISSING" == 1 ]]; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_wallet_recharge_worker')")" \
			|| fail 'The local Tenant wallet worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant worker role exists without its owner-private credential; refusing to rotate it.'
		local wallet_recharge_worker_password
		wallet_recharge_worker_password="$(openssl rand -hex 32)"
		write_private "$TENANT_DIRECTORY/wallet-recharge-worker-password" <<<"$wallet_recharge_worker_password"
		write_private "$TENANT_DIRECTORY/wallet-recharge-worker.properties" <<EOF
credential-secret-reference=$WALLET_RECHARGE_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_wallet_recharge_worker
password=$wallet_recharge_worker_password
EOF
		unset wallet_recharge_worker_password
		WORKER_CREDENTIALS_MISSING=0
	fi
	if ! grep -q '^wallet-recharge-callback-credential-reference=' "$TENANT_DIRECTORY/state.properties"; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_wallet_recharge_worker')")" \
			|| fail 'The local Tenant wallet worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant worker role exists without complete owner-private state; refusing to rotate it.'
		local replacement="$TENANT_DIRECTORY/.state.properties.$$.tmp"
		cat "$TENANT_DIRECTORY/state.properties" > "$replacement"
		printf 'wallet-recharge-callback-credential-reference=%s\n' "$WALLET_RECHARGE_SECRET_REFERENCE" >> "$replacement"
		chmod 600 "$replacement"
		mv -- "$replacement" "$TENANT_DIRECTORY/state.properties"
	fi
}

ensure_business_documents_worker_credentials() {
	local role_exists
	BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE="local-tenant-business-documents-worker:$TENANT_ID"
	if [[ "$BUSINESS_DOCUMENTS_WORKER_CREDENTIALS_MISSING" == 1 ]]; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_business_documents_worker')")" \
			|| fail 'The local Tenant Business Documents worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant document worker role exists without its owner-private credential; refusing to regenerate it.'
		local business_documents_worker_password
		business_documents_worker_password="$(openssl rand -hex 32)"
		write_private "$TENANT_DIRECTORY/business-documents-worker-password" <<<"$business_documents_worker_password"
		write_private "$TENANT_DIRECTORY/business-documents-worker.properties" <<EOF
credential-secret-reference=$BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_business_documents_worker
password=$business_documents_worker_password
EOF
		unset business_documents_worker_password
		BUSINESS_DOCUMENTS_WORKER_CREDENTIALS_MISSING=0
	fi
	if ! grep -q '^business-documents-worker-credential-reference=' "$TENANT_DIRECTORY/state.properties"; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_business_documents_worker')")" \
			|| fail 'The local Tenant Business Documents worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant document worker role exists without complete owner-private state; refusing to rotate it.'
		local replacement="$TENANT_DIRECTORY/.state.properties.$$.tmp"
		cat "$TENANT_DIRECTORY/state.properties" > "$replacement"
		printf 'business-documents-worker-credential-reference=%s\n' "$BUSINESS_DOCUMENTS_WORKER_SECRET_REFERENCE" >> "$replacement"
		chmod 600 "$replacement"
		mv -- "$replacement" "$TENANT_DIRECTORY/state.properties"
	fi
}

ensure_payment_callback_worker_credentials() {
	local role_exists
	PAYMENT_CALLBACK_SECRET_REFERENCE="local-tenant-payment-callback-worker:$TENANT_ID"
	if [[ "$PAYMENT_WORKER_CREDENTIALS_MISSING" == 1 ]]; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_payments_worker')")" \
			|| fail 'The local Tenant Payments worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant Payments worker role exists without its owner-private credential; refusing to rotate it.'
		local payment_callback_worker_password
		payment_callback_worker_password="$(openssl rand -hex 32)"
		write_private "$TENANT_DIRECTORY/payment-callback-worker-password" <<<"$payment_callback_worker_password"
		write_private "$TENANT_DIRECTORY/payment-callback-worker.properties" <<EOF
credential-secret-reference=$PAYMENT_CALLBACK_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_payments_worker
password=$payment_callback_worker_password
EOF
		unset payment_callback_worker_password
		PAYMENT_WORKER_CREDENTIALS_MISSING=0
	fi
	if ! grep -q '^payment-callback-worker-credential-reference=' "$TENANT_DIRECTORY/state.properties"; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_payments_worker')")" \
			|| fail 'The local Tenant Payments worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant Payments role exists without complete owner-private state; refusing to rotate it.'
		local replacement="$TENANT_DIRECTORY/.state.properties.$$.tmp"
		cat "$TENANT_DIRECTORY/state.properties" > "$replacement"
		printf 'payment-callback-worker-credential-reference=%s\n' "$PAYMENT_CALLBACK_SECRET_REFERENCE" >> "$replacement"
		chmod 600 "$replacement"
		mv -- "$replacement" "$TENANT_DIRECTORY/state.properties"
	fi
}

ensure_business_traceability_worker_credentials() {
	local role_exists
	BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE="local-tenant-business-traceability-worker:$TENANT_ID"
	if [[ "$BUSINESS_TRACEABILITY_WORKER_CREDENTIALS_MISSING" == 1 ]]; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_business_traceability_worker')")" \
			|| fail 'The local Tenant traceability worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant traceability worker role exists without its owner-private credential; refusing to rotate it.'
		local business_traceability_worker_password
		business_traceability_worker_password="$(openssl rand -hex 32)"
		write_private "$TENANT_DIRECTORY/business-traceability-worker-password" <<<"$business_traceability_worker_password"
		write_private "$TENANT_DIRECTORY/business-traceability-worker.properties" <<EOF
credential-secret-reference=$BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE
jdbc-url=jdbc:postgresql://127.0.0.1:$DATABASE_PORT/nexa_tenant_business
api-jdbc-url=$TENANT_API_JDBC_URL
username=nexa_business_traceability_worker
password=$business_traceability_worker_password
EOF
		unset business_traceability_worker_password
		BUSINESS_TRACEABILITY_WORKER_CREDENTIALS_MISSING=0
	fi
	if ! grep -q '^business-traceability-worker-credential-reference=' "$TENANT_DIRECTORY/state.properties"; then
		role_exists="$(tenant_compose exec -T tenant-database psql -X -qAt -v ON_ERROR_STOP=1 \
			-U nexa_tenant_bootstrap_admin -d nexa_tenant_business \
			-c "SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nexa_business_traceability_worker')")" \
			|| fail 'The local Tenant traceability worker role could not be inspected.'
		[[ "$role_exists" == f ]] || fail 'The Tenant traceability role exists without complete owner-private state; refusing to rotate it.'
		local replacement="$TENANT_DIRECTORY/.state.properties.$$.tmp"
		cat "$TENANT_DIRECTORY/state.properties" > "$replacement"
		printf 'business-traceability-worker-credential-reference=%s\n' "$BUSINESS_TRACEABILITY_WORKER_SECRET_REFERENCE" >> "$replacement"
		chmod 600 "$replacement"
		mv -- "$replacement" "$TENANT_DIRECTORY/state.properties"
	fi
}

verify_central_scope
binding_snapshot="$(read_binding)"
if [[ "$WORKSPACE_ANCHOR_ONLY" == 1 ]]; then
	[[ -n "$binding_snapshot" ]] || fail 'The Tenant has no central database binding; refusing to provision a database from a Workspace anchor task.'
	load_existing_state
	IFS='|' read -r bound_identity bound_reference binding_state binding_version <<<"$binding_snapshot"
	[[ "$bound_identity" == "$DATABASE_IDENTITY" && "$bound_reference" == "$SECRET_REFERENCE" \
		&& "$binding_state" == READY ]] || fail 'The Tenant binding is not the matching READY database identity.'
	WALLET_RECHARGE_SECRET_REFERENCE="local-tenant-wallet-recharge-worker:$TENANT_ID"
	registered_worker_reference="$(read_wallet_recharge_callback_reference)"
	[[ "$registered_worker_reference" == "$WALLET_RECHARGE_SECRET_REFERENCE" ]] \
		|| fail 'The Tenant binding does not match the owner-private wallet worker identity.'
	PAYMENT_CALLBACK_SECRET_REFERENCE="local-tenant-payment-callback-worker:$TENANT_ID"
	registered_payment_worker_reference="$(read_payment_callback_credential_reference)"
	[[ "$registered_payment_worker_reference" == "$PAYMENT_CALLBACK_SECRET_REFERENCE" ]] \
		|| fail 'The Tenant binding does not match the owner-private Payments worker identity.'
	SCHEMA_MANIFEST_DIGEST="$(read_schema_manifest_digest)"
	if deferred_task="$(defer_workspace_anchor_for_schema_upgrade)" && [[ "$deferred_task" == "$WORKSPACE_ANCHOR_TASK_ID" ]]; then
		printf '%s\n' 'Tenant schema manifest changed; queued the existing Tenant database task for migration before Workspace projection.'
		exit 75
	fi
	[[ -e "$VOLUME_MARKER" && -f "$VOLUME_MARKER" ]] \
		|| fail 'The Tenant database has no local volume history; refusing workspace-only provisioning.'
	docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1 \
		|| fail 'The READY Tenant database volume is missing; restore it before projecting a Workspace anchor.'
	verify_workspace_anchor_claim
	tenant_compose config --quiet >/dev/null
	tenant_compose up -d tenant-database
	ensure_api_tenant_network
	ready=0
	for _ in $(seq 1 60); do
		if tenant_compose exec -T tenant-database pg_isready -U nexa_tenant_bootstrap_admin -d nexa_tenant_business >/dev/null 2>&1; then ready=1; break; fi
		sleep 1
	done
	[[ "$ready" == 1 ]] || fail 'The existing Tenant PostgreSQL service is not ready for scope projection.'
	host_ready=0
	for _ in $(seq 1 30); do
		if ( exec 3<>"/dev/tcp/127.0.0.1/$DATABASE_PORT" ) 2>/dev/null; then host_ready=1; break; fi
		sleep 1
	done
	[[ "$host_ready" == 1 ]] || fail 'The existing Tenant PostgreSQL loopback port is not reachable.'
	verify_workspace_anchor_claim
	tenant_compose exec -T tenant-database psql -X -q -v ON_ERROR_STOP=1 \
		-v "tenant_id=$TENANT_ID" -v "workspace_id=$WORKSPACE_ID" -v "database_identity=$DATABASE_IDENTITY" \
		-U nexa_tenant_bootstrap_admin -d nexa_tenant_business < "$SCOPE_SQL_FILE"
	run_cli verify
	verify_api_tenant_database_connection
	verify_workspace_anchor_claim
	printf '%s\n' 'Workspace scope anchor projected into the existing Tenant database; Tenant binding state was not changed.'
	exit 0
fi
if [[ -n "$binding_snapshot" ]]; then
	load_existing_state
	IFS='|' read -r bound_identity bound_reference binding_state binding_version <<<"$binding_snapshot"
	[[ "$bound_identity" == "$DATABASE_IDENTITY" && "$bound_reference" == "$SECRET_REFERENCE" ]] || fail 'Central binding differs from local private state; refusing to replace either identity.'
		WALLET_RECHARGE_SECRET_REFERENCE="local-tenant-wallet-recharge-worker:$TENANT_ID"
		registered_worker_reference="$(read_wallet_recharge_callback_reference)"
		[[ -z "$registered_worker_reference" || "$registered_worker_reference" == "$WALLET_RECHARGE_SECRET_REFERENCE" ]] \
			|| fail 'The central wallet worker reference differs from local private state; refusing to replace it.'
	PAYMENT_CALLBACK_SECRET_REFERENCE="local-tenant-payment-callback-worker:$TENANT_ID"
	registered_payment_worker_reference="$(read_payment_callback_credential_reference)"
	[[ -z "$registered_payment_worker_reference" || "$registered_payment_worker_reference" == "$PAYMENT_CALLBACK_SECRET_REFERENCE" ]] \
		|| fail 'The central Payments worker reference differs from local private state; refusing to replace it.'
	if [[ "$binding_state" == READY ]] && ! docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
		fail 'The central binding is READY but its Tenant database volume is missing; inspect or restore it manually.'
	fi
	if [[ -e "$VOLUME_MARKER" ]] && ! docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
		fail 'The Tenant database volume is missing after creation began; inspect or restore it manually before retrying.'
	fi
	if [[ "$binding_state" == READY ]]; then
		cas_from_state='READY'
	else
		[[ "$binding_state" != SUSPENDED ]] || fail 'The central Tenant database binding is SUSPENDED.'
		if [[ "$binding_state" == FAILED && "$RETRY_FAILED" -ne 1 ]]; then fail 'The binding is FAILED; use --retry-failed for an exact CAS retry.'; fi
		[[ "$binding_state" == PROVISIONING || "$binding_state" == FAILED ]] || fail 'The central Tenant database binding is in an unsupported state.'
		cas_from_state="$binding_state"
	fi
	DATABASE_IDENTITY="$bound_identity" SECRET_REFERENCE="$bound_reference" BINDING_VERSION="$binding_version"
	cas_version="$(central_psql <<SQL
WITH changed AS (
  UPDATE tenant_management.tenant_business_database_binding
		SET lifecycle_state='PROVISIONING', verified_schema_manifest_sha256=NULL,
         version=version+1, updated_at=current_timestamp
   WHERE tenant_id=:'tenant_id'::UUID AND database_identity=:'database_identity'::UUID
     AND credential_secret_reference=:'secret_reference' AND lifecycle_state='$cas_from_state'
     AND version=:'expected_version'::BIGINT
     AND (:'task_guard'::BOOLEAN = FALSE OR EXISTS (
         SELECT 1 FROM tenant_management.tenant_business_database_provisioning_task task
          WHERE task.task_id=:'task_id'::UUID AND task.tenant_id=:'tenant_id'::UUID
            AND task.workspace_id=:'workspace_id'::UUID AND task.status='LEASED'
            AND task.claim_token=:'task_claim_token'::UUID AND task.lease_until>current_timestamp
     ))
     RETURNING version
)
SELECT version FROM changed;
SQL
 )"
	[[ -n "$cas_version" ]] || fail 'The central binding changed during its version check; no database was modified.'
	BINDING_VERSION="$cas_version" BINDING_ACTIVE=1
else
	if docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then fail 'A Tenant volume exists without a central binding; inspect it manually.'; fi
	if [[ -e "$TENANT_DIRECTORY" ]]; then
		load_existing_state
		if [[ -e "$VOLUME_MARKER" ]]; then fail 'Local Tenant state records prior volume creation but the central binding is absent; inspect it manually.'; fi
	else
		create_local_state
	fi
	verify_central_scope
	initial_binding="$(central_psql <<'SQL'
INSERT INTO tenant_management.tenant_business_database_binding
  (tenant_id, database_identity, credential_secret_reference, lifecycle_state,
   wallet_recharge_callback_credential_secret_reference, payment_callback_credential_secret_reference)
SELECT :'tenant_id'::UUID, :'database_identity'::UUID, :'secret_reference', 'PROVISIONING',
       :'wallet_recharge_secret_reference', :'payment_callback_secret_reference'
WHERE :'task_guard'::BOOLEAN = FALSE OR EXISTS (
    SELECT 1 FROM tenant_management.tenant_business_database_provisioning_task task
     WHERE task.task_id=:'task_id'::UUID AND task.tenant_id=:'tenant_id'::UUID
       AND task.workspace_id=:'workspace_id'::UUID AND task.status='LEASED'
       AND task.claim_token=:'task_claim_token'::UUID AND task.lease_until>current_timestamp
)
ON CONFLICT (tenant_id) DO NOTHING;
SELECT database_identity::TEXT || '|' || credential_secret_reference || '|' || lifecycle_state || '|' || version::TEXT
FROM tenant_management.tenant_business_database_binding WHERE tenant_id=:'tenant_id'::UUID;
SQL
 )"
	IFS='|' read -r bound_identity bound_reference binding_state binding_version <<<"$initial_binding"
	[[ "$bound_identity" == "$DATABASE_IDENTITY" && "$bound_reference" == "$SECRET_REFERENCE" && "$binding_state" == PROVISIONING && "$binding_version" == 0 ]] || fail 'The central binding was concurrently created with different state.'
	BINDING_VERSION="$binding_version" BINDING_ACTIVE=1
fi

tenant_compose config --quiet >/dev/null
if [[ ! -e "$VOLUME_MARKER" ]]; then
	[[ ! -L "$VOLUME_MARKER" ]] || fail 'Local Tenant volume history cannot be a symbolic link.'
	( set -o noclobber; : > "$VOLUME_MARKER" ) 2>/dev/null || fail 'Local Tenant volume history appeared concurrently; inspect it manually.'
	chmod 600 "$VOLUME_MARKER"
fi
tenant_compose up -d tenant-database
ensure_api_tenant_network
ready=0
for _ in $(seq 1 60); do
	if tenant_compose exec -T tenant-database pg_isready -U nexa_tenant_bootstrap_admin -d nexa_tenant_business >/dev/null 2>&1; then ready=1; break; fi
	sleep 1
done
[[ "$ready" == 1 ]] || fail 'The local Tenant PostgreSQL service did not become ready.'
ensure_wallet_recharge_worker_credentials
ensure_business_documents_worker_credentials
ensure_payment_callback_worker_credentials
ensure_business_traceability_worker_credentials

host_ready=0
for _ in $(seq 1 30); do
	if ( exec 3<>"/dev/tcp/127.0.0.1/$DATABASE_PORT" ) 2>/dev/null; then
		host_ready=1
		break
	fi
	sleep 1
done
[[ "$host_ready" == 1 ]] || fail 'The local Tenant PostgreSQL loopback port did not become reachable.'

tenant_compose exec -T tenant-database psql -X -q -v ON_ERROR_STOP=1 \
	-v "tenant_id=$TENANT_ID" -v "database_identity=$DATABASE_IDENTITY" \
	-U nexa_tenant_bootstrap_admin -d nexa_tenant_business <<'SQL'
SELECT set_config('nexa.local.provision.tenant_id', :'tenant_id', false) AS _tenant_id \gset
SELECT set_config('nexa.local.provision.database_identity', :'database_identity', false) AS _database_identity \gset
DO $preflight$
DECLARE
  identity_exists BOOLEAN := to_regclass('nexa_platform.tenant_business_database_identity') IS NOT NULL;
  history_exists BOOLEAN := to_regclass('public.flyway_schema_history') IS NOT NULL;
  expected_tenant UUID := current_setting('nexa.local.provision.tenant_id', true)::UUID;
  expected_database UUID := current_setting('nexa.local.provision.database_identity', true)::UUID;
  identity_rows BIGINT;
BEGIN
  IF identity_exists <> history_exists THEN RAISE EXCEPTION 'Tenant migration history and identity are inconsistent'; END IF;
  IF NOT identity_exists THEN
    IF EXISTS (SELECT 1 FROM pg_namespace
               WHERE nspname NOT IN ('pg_catalog','information_schema','public','pg_toast')
                 AND nspname !~ '^pg_(temp|toast_temp)_[0-9]+$')
       OR EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S','f'))
       OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace='public'::regnamespace)
       OR EXISTS (SELECT 1 FROM pg_type
                 WHERE typnamespace='public'::regnamespace AND typrelid=0 AND typtype <> 'p')
       OR EXISTS (SELECT 1 FROM pg_extension WHERE extnamespace='public'::regnamespace) THEN
      RAISE EXCEPTION 'Tenant database is not empty and has no trusted identity';
    END IF;
    RETURN;
  END IF;
  SELECT count(*) INTO identity_rows FROM nexa_platform.tenant_business_database_identity;
  IF identity_rows > 1 OR (identity_rows = 1 AND NOT EXISTS (
      SELECT 1 FROM nexa_platform.tenant_business_database_identity
       WHERE singleton=TRUE AND tenant_id=expected_tenant AND database_identity=expected_database
  )) THEN RAISE EXCEPTION 'Tenant database identity does not match the central binding'; END IF;
END
$preflight$;
SQL

	wallet_recharge_worker_password="$(read_value "$TENANT_DIRECTORY/wallet-recharge-worker.properties" password)"
business_documents_worker_password="$(read_value "$TENANT_DIRECTORY/business-documents-worker.properties" password)"
	payment_callback_worker_password="$(read_value "$TENANT_DIRECTORY/payment-callback-worker.properties" password)"
business_traceability_worker_password="$(read_value "$TENANT_DIRECTORY/business-traceability-worker.properties" password)"
{
	printf '\\set wallet_recharge_worker_password %s\n' "$wallet_recharge_worker_password"
	printf '\\set business_documents_worker_password %s\n' "$business_documents_worker_password"
	printf '\\set payments_worker_password %s\n' "$payment_callback_worker_password"
	printf '\\set business_traceability_worker_password %s\n' "$business_traceability_worker_password"
	cat "$ROLE_SQL_FILE"
} | tenant_compose exec -T tenant-database sh -ec '
	export NEXA_LOCAL_MIGRATOR_PASSWORD="$(cat /run/secrets/migrator_password)"
	export NEXA_LOCAL_RUNTIME_PASSWORD="$(cat /run/secrets/runtime_password)"
	export NEXA_LOCAL_POLICY_SNAPSHOT_WRITER_PASSWORD="$(cat /run/secrets/policy_snapshot_writer_password)"
	exec psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"
'
unset wallet_recharge_worker_password
unset business_documents_worker_password
unset payment_callback_worker_password
unset business_traceability_worker_password

run_cli migrate
tenant_compose exec -T tenant-database psql -X -q -v ON_ERROR_STOP=1 \
	-v "tenant_id=$TENANT_ID" -v "workspace_id=$WORKSPACE_ID" -v "database_identity=$DATABASE_IDENTITY" \
	-U nexa_tenant_bootstrap_admin -d nexa_tenant_business < "$SCOPE_SQL_FILE"
verify_central_scope
run_cli verify
verify_api_tenant_database_connection
SCHEMA_MANIFEST_DIGEST="$(read_schema_manifest_digest)"

ready_result="$(central_psql <<'SQL'
WITH changed AS (
  UPDATE tenant_management.tenant_business_database_binding
     SET lifecycle_state='READY', verified_schema_manifest_sha256=:'schema_manifest_digest',
         wallet_recharge_callback_credential_secret_reference=:'wallet_recharge_secret_reference',
         payment_callback_credential_secret_reference=:'payment_callback_secret_reference',
         version=version+1, updated_at=current_timestamp
   WHERE tenant_id=:'tenant_id'::UUID AND database_identity=:'database_identity'::UUID
     AND credential_secret_reference=:'secret_reference' AND lifecycle_state='PROVISIONING'
     AND version=:'expected_version'::BIGINT
     AND (:'task_guard'::BOOLEAN = FALSE OR EXISTS (
         SELECT 1 FROM tenant_management.tenant_business_database_provisioning_task task
          WHERE task.task_id=:'task_id'::UUID AND task.tenant_id=:'tenant_id'::UUID
            AND task.workspace_id=:'workspace_id'::UUID AND task.status='LEASED'
            AND task.claim_token=:'task_claim_token'::UUID AND task.lease_until>current_timestamp
     ))
     RETURNING tenant_id
)
SELECT count(*) FROM changed;
SQL
 )"
[[ "$ready_result" == 1 ]] || fail 'Local checks passed, but the central binding READY transition failed its version check.'
BINDING_ACTIVE=0
printf '%s\n' 'Local Tenant database provisioning completed and the central binding is READY. No production routing or cutover was performed.'
