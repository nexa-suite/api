#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
API_DIRECTORY="$(cd -- "$SCRIPT_DIRECTORY/../.." && pwd)"
ENV_FILE="$API_DIRECTORY/.env.local"
COMPOSE_FILE="$API_DIRECTORY/ops/compose/modern.compose.yml"
STATE_DIRECTORY="$API_DIRECTORY/.local/tenant-provisioning-worker"
TENANT_STATE_DIRECTORY="$API_DIRECTORY/.local/tenant-databases"
PID_FILE="$STATE_DIRECTORY/worker.pid"
READY_FILE="$STATE_DIRECTORY/worker.ready"
CENTRAL_CONFIG_FILE="$STATE_DIRECTORY/central-postgres.properties"
PROVISIONER="$SCRIPT_DIRECTORY/provision-local-tenant-database.sh"
STOPPING=0 ACTIVE_CHILD='' HEARTBEAT_PID=''
RECONCILE_SCHEMA_MANIFEST=0
ONE_SHOT=0 ONE_SHOT_TASK_ID='' ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR=0 CENTRAL_CONFIG_OVERRIDE='' LOCAL_STATE_OVERRIDE=''
UUID_PATTERN='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

fail() { printf '%s\n' "$1" >&2; exit 1; }
usage() {
	printf '%s\n' \
		'Usage: tenant-business-database-provisioning-worker.sh [--reconcile-schema-manifest]' \
		'       tenant-business-database-provisioning-worker.sh --once --task-id <uuid> [--include-workspace-anchor] --central-configfile <private-file> --local-state-dir <absolute-private-directory>' >&2
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		--reconcile-schema-manifest)
			[[ "$RECONCILE_SCHEMA_MANIFEST" == 0 ]] || { usage; exit 2; }
			RECONCILE_SCHEMA_MANIFEST=1
			shift
			;;
		--once)
			[[ "$ONE_SHOT" == 0 ]] || { usage; exit 2; }
			ONE_SHOT=1
			shift
			;;
		--task-id)
			[[ $# -ge 2 && -z "$ONE_SHOT_TASK_ID" ]] || { usage; exit 2; }
			ONE_SHOT_TASK_ID="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')"
			shift 2
			;;
		--include-workspace-anchor)
			[[ "$ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR" == 0 ]] || { usage; exit 2; }
			ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR=1
			shift
			;;
		--central-configfile)
			[[ $# -ge 2 && -z "$CENTRAL_CONFIG_OVERRIDE" ]] || { usage; exit 2; }
			CENTRAL_CONFIG_OVERRIDE="$2"
			shift 2
			;;
		--local-state-dir)
			[[ $# -ge 2 && -z "$LOCAL_STATE_OVERRIDE" ]] || { usage; exit 2; }
			LOCAL_STATE_OVERRIDE="$2"
			shift 2
			;;
		*) usage; exit 2 ;;
	esac
done

if [[ "$ONE_SHOT" == 1 ]]; then
	[[ "$RECONCILE_SCHEMA_MANIFEST" == 0 && "$ONE_SHOT_TASK_ID" =~ $UUID_PATTERN \
		&& "$CENTRAL_CONFIG_OVERRIDE" == /* && "$LOCAL_STATE_OVERRIDE" == /* ]] || { usage; exit 2; }
	CENTRAL_CONFIG_FILE="$CENTRAL_CONFIG_OVERRIDE"
	TENANT_STATE_DIRECTORY="$LOCAL_STATE_OVERRIDE"
elif [[ -n "$ONE_SHOT_TASK_ID$CENTRAL_CONFIG_OVERRIDE$LOCAL_STATE_OVERRIDE" \
	|| "$ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR" == 1 ]]; then
	usage
	exit 2
fi

PID_FILE="$STATE_DIRECTORY/worker.pid"
READY_FILE="$STATE_DIRECTORY/worker.ready"
if [[ "$ONE_SHOT" == 1 ]]; then
	CENTRAL_CONFIG_FILE="$CENTRAL_CONFIG_OVERRIDE"
else
	CENTRAL_CONFIG_FILE="$STATE_DIRECTORY/central-postgres.properties"
fi
CENTRAL_CONTAINER_ID=''
private_mode() {
	local path="$1" expected="$2" actual
	[[ ! -L "$path" ]] || return 1
	actual="$(stat -c '%a' "$path" 2>/dev/null)" || actual="$(stat -f '%Lp' "$path" 2>/dev/null)" || return 1
	[[ "$actual" =~ ^[0-7]{3,4}$ && "$actual" == "$expected" ]]
}
physical_private_directory() {
	local path="$1"
	[[ "$path" == /* && -d "$path" && ! -L "$path" ]] || return 1
	private_mode "$path" 700 || return 1
	(cd -- "$path" && pwd -P)
}
path_is_within() {
	local path="$1" parent="$2"
	[[ "$path" == "$parent" || "$path" == "$parent/"* ]]
}
directory_is_empty() {
	[[ -z "$(find "$1" -mindepth 1 -maxdepth 1 -print -quit)" ]]
}
validate_one_shot_tenant_state() {
	local tenant_id="$1" entry lock_entry lock_name
	[[ "$tenant_id" =~ $UUID_PATTERN ]] || fail 'The one-shot Tenant scope is invalid.'
	while IFS= read -r -d '' entry; do
		if [[ "$entry" == "$TENANT_STATE_DIRECTORY/$tenant_id" ]]; then
			[[ -d "$entry" && ! -L "$entry" ]] \
				|| fail 'One-shot local state may contain only the selected Tenant directory.'
			private_mode "$entry" 700 || fail 'One-shot Tenant state must be owner-only.'
		elif [[ "$entry" == "$TENANT_STATE_DIRECTORY/.locks" ]]; then
			[[ -d "$entry" && ! -L "$entry" ]] \
				|| fail 'One-shot provisioning lock state must be a private directory.'
			private_mode "$entry" 700 || fail 'One-shot provisioning lock state must be owner-only.'
			while IFS= read -r -d '' lock_entry; do
				lock_name="${lock_entry##*/}"
				[[ "$lock_name" =~ $UUID_PATTERN && -d "$lock_entry" && ! -L "$lock_entry" ]] \
					|| fail 'One-shot provisioning lock state contains an invalid Tenant entry.'
				private_mode "$lock_entry" 700 || fail 'One-shot provisioning Tenant lock must be owner-only.'
			done < <(find "$entry" -mindepth 1 -maxdepth 1 -print0)
		else
			fail 'One-shot local state may contain only the selected Tenant directory and provisioning locks.'
		fi
	done < <(find "$TENANT_STATE_DIRECTORY" -mindepth 1 -maxdepth 1 -print0)
}
validate_one_shot_state() {
	local tenant_path repository_path local_path config_directory config_path
	tenant_path="$(physical_private_directory "$TENANT_STATE_DIRECTORY")" \
		|| fail 'The one-shot local state must be an existing absolute owner-only directory.'
	repository_path="$(cd -- "$API_DIRECTORY" && pwd -P)" \
		|| fail 'The API repository path could not be resolved.'
	local_path="$repository_path/.local"
	if [[ -e "$API_DIRECTORY/.local" || -L "$API_DIRECTORY/.local" ]]; then
		[[ -d "$API_DIRECTORY/.local" && ! -L "$API_DIRECTORY/.local" ]] \
			|| fail 'The development .local path is unsafe; one-shot state remains untouched.'
		local_path="$(cd -- "$API_DIRECTORY/.local" && pwd -P)" \
			|| fail 'The development .local path could not be resolved.'
	fi
	[[ -f "$CENTRAL_CONFIG_FILE" && ! -L "$CENTRAL_CONFIG_FILE" ]] \
		|| fail 'The one-shot central config must be an existing absolute regular file.'
	private_mode "$CENTRAL_CONFIG_FILE" 600 || fail 'The one-shot central config must be owner-only 0600.'
	config_directory="$(dirname -- "$CENTRAL_CONFIG_FILE")"
	private_mode "$config_directory" 700 || fail 'The one-shot central config directory must be owner-only 0700.'
	config_directory="$(cd -- "$config_directory" && pwd -P)" \
		|| fail 'The one-shot central config directory could not be resolved.'
	config_path="$config_directory/$(basename -- "$CENTRAL_CONFIG_FILE")"
	if path_is_within "$tenant_path" "$local_path" || path_is_within "$config_path" "$local_path"; then
		fail 'One-shot config and local state must stay outside the development .local tree.'
	fi
	TENANT_STATE_DIRECTORY="$tenant_path"
	if [[ "$ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR" != 1 ]]; then
		directory_is_empty "$TENANT_STATE_DIRECTORY" || fail 'The one-shot local state directory must be empty.'
	fi
	awk -F= '
		NF != 2 || $1 != "container-id" || $2 !~ /^[0-9a-f]{64}$/ { invalid = 1 }
		{ count++ }
		END { exit (invalid || count != 1) }
	' "$CENTRAL_CONFIG_FILE" || fail 'The one-shot central config must contain only one lowercase container-id entry.'
	CENTRAL_CONTAINER_ID="$(awk -F= '$1 == "container-id" { print $2 }' "$CENTRAL_CONFIG_FILE")"
}
new_uuid() {
	local value
	value="$(openssl rand -hex 16)"
	printf '%s-%s-%s-%s-%s\n' "${value:0:8}" "${value:8:4}" "${value:12:4}" "${value:16:4}" "${value:20:12}"
}
provisioner_psql() {
	if [[ -n "$CENTRAL_CONTAINER_ID" ]]; then
		{
			printf '%s\n' "$PROVISIONER_PASSWORD"
			cat
		} | docker exec -i "$CENTRAL_CONTAINER_ID" sh -ec '
			umask 077
			passfile="$(mktemp)" || exit 70
			cleanup() {
				status=$?
				trap - EXIT HUP INT TERM
				rm -f -- "$passfile" || status=70
				exit "$status"
			}
			trap cleanup EXIT
			trap "exit 129" HUP
			trap "exit 130" INT
			trap "exit 143" TERM
			chmod 600 "$passfile" || exit 70
			IFS= read -r password || exit 70
			case "$password" in ""|*[!A-Za-z0-9+/=_-]*) exit 70 ;; esac
			case "$password" in
				*[+/]*) case "$password" in *[_-]*) exit 70 ;; esac ;;
			esac
			case "$password" in
				*==) padding=2; pass_body=${password%==} ;;
				*=) padding=1; pass_body=${password%=} ;;
				*) padding=0; pass_body=$password ;;
			esac
			case "$pass_body" in *"="*) exit 70 ;; esac
			pass_length=${#pass_body}
			case "$padding" in
				0) case "$((pass_length % 4))" in 0|2|3) ;; *) exit 70 ;; esac ;;
				1) [ "$((pass_length % 4))" -eq 3 ] || exit 70 ;;
				2) [ "$((pass_length % 4))" -eq 2 ] || exit 70 ;;
			esac
			printf "localhost:*:*:nexa_tenant_database_provisioner:%s\\n" "$password" > "$passfile" || exit 70
			unset password pass_body
			unset PGPASSWORD
			PGPASSFILE="$passfile" psql -X -qAt -w -v ON_ERROR_STOP=1 \
				-U nexa_tenant_database_provisioner -d "$POSTGRES_DB"
		' 2>/dev/null
	else
		{
			printf '%s\n' "$PROVISIONER_PASSWORD"
			cat
		} | docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T modern-postgres sh -ec '
			umask 077
			passfile="$(mktemp)" || exit 70
			cleanup() {
				status=$?
				trap - EXIT HUP INT TERM
				rm -f -- "$passfile" || status=70
				exit "$status"
			}
			trap cleanup EXIT
			trap "exit 129" HUP
			trap "exit 130" INT
			trap "exit 143" TERM
			chmod 600 "$passfile" || exit 70
			IFS= read -r password || exit 70
			case "$password" in ""|*[!A-Za-z0-9+/=_-]*) exit 70 ;; esac
			case "$password" in
				*[+/]*) case "$password" in *[_-]*) exit 70 ;; esac ;;
			esac
			case "$password" in
				*==) padding=2; pass_body=${password%==} ;;
				*=) padding=1; pass_body=${password%=} ;;
				*) padding=0; pass_body=$password ;;
			esac
			case "$pass_body" in *"="*) exit 70 ;; esac
			pass_length=${#pass_body}
			case "$padding" in
				0) case "$((pass_length % 4))" in 0|2|3) ;; *) exit 70 ;; esac ;;
				1) [ "$((pass_length % 4))" -eq 3 ] || exit 70 ;;
				2) [ "$((pass_length % 4))" -eq 2 ] || exit 70 ;;
			esac
			printf "localhost:*:*:nexa_tenant_database_provisioner:%s\\n" "$password" > "$passfile" || exit 70
			unset password pass_body
			unset PGPASSWORD
			PGPASSFILE="$passfile" psql -X -qAt -w -v ON_ERROR_STOP=1 \
				-U nexa_tenant_database_provisioner -d "$POSTGRES_DB"
		' 2>/dev/null
	fi
}
load_provisioner_password() {
	local inherited_password="${NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD:-}"
	if [[ -n "$inherited_password" ]]; then
		PROVISIONER_PASSWORD="$inherited_password"
	else
		[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] \
			|| fail 'The private Modern local environment is unavailable for Tenant provisioning.'
		private_mode "$ENV_FILE" 600 || fail 'The Modern local environment must be owner-only.'
		PROVISIONER_PASSWORD="$(awk -F= '
			$1 == "NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD" {
				count++
				sub(/^[^=]*=/, "")
				print
			}
			END { exit count != 1 }
		' "$ENV_FILE")" || fail 'The local Tenant provisioner credential is missing or ambiguous.'
	fi
	local base64_pattern='^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}(==)?|[A-Za-z0-9+/]{3}=?|[A-Za-z0-9+/]{4})?$'
	local base64url_pattern='^([A-Za-z0-9_-]{4})*([A-Za-z0-9_-]{2}(==)?|[A-Za-z0-9_-]{3}=?|[A-Za-z0-9_-]{4})?$'
	[[ -n "$PROVISIONER_PASSWORD" \
		&& ( "$PROVISIONER_PASSWORD" =~ $base64_pattern || "$PROVISIONER_PASSWORD" =~ $base64url_pattern ) ]] \
		|| fail 'The local Tenant provisioner credential is invalid.'
	unset NEXA_TENANT_DATABASE_PROVISIONER_PASSWORD
}
reconcile_stale_ready_tasks() {
	local manifest_digest task_rows task_id tenant_id workspace_id database_port task_version binding_state requeued_task
	local requeued_count=0
	manifest_digest="$(cd -- "$API_DIRECTORY" && HOME="$STATE_DIRECTORY" MAVEN_USER_HOME="$STATE_DIRECTORY/.m2" \
		./mvnw -q -Dspring-boot.run.main-class=com.nexa.api.bootstrap.runtime.database.tenant.local.LocalTenantBusinessDatabaseProvisioningCli \
		'-Dspring-boot.run.arguments=--mode=manifest-digest' spring-boot:run)" \
		|| { printf '%s\n' 'The active Tenant schema manifest could not be verified; stale bindings remain fail-closed.' >&2; return 1; }
	[[ "$manifest_digest" =~ ^[0-9a-f]{64}$ ]] \
		|| { printf '%s\n' 'The active Tenant schema manifest returned invalid evidence; stale bindings remain fail-closed.' >&2; return 1; }
	task_rows="$(provisioner_psql <<'SQL'
SET app.cross_scope_workspace_scan = 'true';
SELECT task_id::TEXT || '|' || tenant_id::TEXT || '|' || workspace_id::TEXT || '|'
       || database_port::TEXT || '|' || version::TEXT
  FROM tenant_management.tenant_business_database_provisioning_task
 WHERE status='READY' AND database_port IS NOT NULL
 ORDER BY tenant_id,task_id;
SQL
		)" || { printf '%s\n' 'The local Tenant provisioning queue could not be inspected.' >&2; return 1; }
	while IFS='|' read -r task_id tenant_id workspace_id database_port task_version; do
		[[ -n "${task_id:-}" ]] || continue
		[[ "$task_id" =~ ^[0-9a-f-]{36}$ && "$tenant_id" =~ ^[0-9a-f-]{36}$ \
			&& "$workspace_id" =~ ^[0-9a-f-]{36}$ && "$database_port" =~ ^[0-9]{4,5}$ \
			&& "$task_version" =~ ^[0-9]+$ ]] \
			|| { printf '%s\n' 'The local Tenant provisioning queue returned malformed task scope.' >&2; return 1; }
		binding_state="$(provisioner_psql <<SQL
SET app.current_tenant_id = '$tenant_id';
SET app.current_workspace_id = '$workspace_id';
SELECT lifecycle_state || '|' || coalesce(verified_schema_manifest_sha256, '')
  FROM tenant_management.tenant_business_database_binding
 WHERE tenant_id='$tenant_id'::UUID;
SQL
		)" || { printf '%s\n' 'A Tenant database binding could not be checked in its own Tenant/Workspace scope.' >&2; return 1; }
		[[ "$binding_state" == "READY|$manifest_digest" ]] && continue
		[[ "$binding_state" == READY\|* ]] || continue
		requeued_task="$(provisioner_psql <<SQL
SET app.current_tenant_id = '$tenant_id';
SET app.current_workspace_id = '$workspace_id';
UPDATE tenant_management.tenant_business_database_provisioning_task task
   SET status='PENDING',claim_token=NULL,lease_until=NULL,next_attempt_at=current_timestamp,
       failure_code='SCHEMA_MANIFEST_UPGRADE_REQUIRED',completed_at=NULL,
       updated_at=current_timestamp,version=version+1
 WHERE task.task_id='$task_id'::UUID AND task.tenant_id='$tenant_id'::UUID
   AND task.workspace_id='$workspace_id'::UUID AND task.database_port=$database_port
   AND task.version=$task_version AND task.status='READY'
   AND EXISTS (
       SELECT 1 FROM tenant_management.tenant_business_database_binding binding
        WHERE binding.tenant_id=task.tenant_id AND binding.lifecycle_state='READY'
          AND binding.verified_schema_manifest_sha256 IS DISTINCT FROM '$manifest_digest'
   )
RETURNING task_id::TEXT;
SQL
		)" || { printf '%s\n' 'A stale Tenant database task could not be safely requeued.' >&2; return 1; }
		[[ "$requeued_task" == "$task_id" ]] && requeued_count=$((requeued_count + 1))
	done <<< "$task_rows"
	printf 'Queued %s local Tenant database schema upgrade(s) for the active manifest.\n' "$requeued_count"
}
current_task_claimed() {
	local task_id="$1" claim_token="$2" lease_seconds="$3"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
UPDATE tenant_management.tenant_business_database_provisioning_task
   SET lease_until=current_timestamp + interval '$lease_seconds seconds'
 WHERE task_id='$task_id'::UUID AND status='LEASED' AND claim_token='$claim_token'::UUID
   AND lease_until>current_timestamp
RETURNING task_id;
SQL
}
current_workspace_anchor_claimed() {
	local task_id="$1" claim_token="$2" lease_seconds="$3"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
UPDATE tenant_management.tenant_business_database_workspace_anchor_task
   SET lease_until=current_timestamp + interval '$lease_seconds seconds'
 WHERE task_id='$task_id'::UUID AND status='LEASED' AND claim_token='$claim_token'::UUID
   AND lease_until>current_timestamp
RETURNING task_id;
SQL
}
mark_task_ready() {
	local task_id="$1" claim_token="$2" tenant_id="$3"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
SET app.current_tenant_id = '$tenant_id';
UPDATE tenant_management.tenant_business_database_provisioning_task task
   SET status='READY', claim_token=NULL, lease_until=NULL, failure_code=NULL,
       completed_at=current_timestamp, updated_at=current_timestamp, version=version+1
 WHERE task.task_id='$task_id'::UUID AND task.status='LEASED'
   AND task.claim_token='$claim_token'::UUID AND task.lease_until>current_timestamp
   AND EXISTS (
       SELECT 1 FROM tenant_management.tenant_business_database_binding binding
        WHERE binding.tenant_id=task.tenant_id AND binding.lifecycle_state='READY'
          AND binding.verified_schema_manifest_sha256 IS NOT NULL
          AND binding.wallet_recharge_callback_credential_secret_reference IS NOT NULL
   )
	RETURNING task_id;
SQL
}
mark_task_failed() {
	local task_id="$1" claim_token="$2"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
UPDATE tenant_management.tenant_business_database_provisioning_task
   SET status='FAILED', claim_token=NULL, lease_until=NULL, failure_code='PROVISIONER_FAILED',
       next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second'),
       completed_at=NULL, updated_at=current_timestamp, version=version+1
 WHERE task_id='$task_id'::UUID AND status='LEASED' AND claim_token='$claim_token'::UUID
   AND lease_until>current_timestamp
RETURNING task_id;
SQL
}
claim_next_task() {
	local candidate_port="$1" claim_token="$2"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
WITH candidate AS (
    SELECT task_id
      FROM tenant_management.tenant_business_database_provisioning_task
     WHERE (status IN ('PENDING','FAILED') AND next_attempt_at <= current_timestamp)
        OR (status='LEASED' AND lease_until <= current_timestamp)
     ORDER BY created_at,task_id
     FOR UPDATE SKIP LOCKED
     LIMIT 1
), claimed AS (
    UPDATE tenant_management.tenant_business_database_provisioning_task task
       SET status='LEASED', attempt_count=attempt_count+1,
           database_port=coalesce(database_port,$candidate_port),
           claim_token='$claim_token'::UUID,
           lease_until=current_timestamp + interval '2 minutes', next_attempt_at=current_timestamp,
           failure_code=NULL, completed_at=NULL, updated_at=current_timestamp, version=version+1
      FROM candidate
     WHERE task.task_id=candidate.task_id
    RETURNING task.task_id,task.tenant_id,task.workspace_id,task.database_port,task.claim_token
)
SELECT task_id::TEXT || '|' || tenant_id::TEXT || '|' || workspace_id::TEXT || '|' || database_port::TEXT
       || '|' || claim_token::TEXT
  FROM claimed;
SQL
}
claim_task_by_id() {
	local task_id="$1" candidate_port="$2" claim_token="$3"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
WITH candidate AS (
    SELECT task_id
      FROM tenant_management.tenant_business_database_provisioning_task
     WHERE task_id='$task_id'::UUID
       AND ((status IN ('PENDING','FAILED') AND next_attempt_at <= current_timestamp)
        OR (status='LEASED' AND lease_until <= current_timestamp))
     FOR UPDATE SKIP LOCKED
     LIMIT 1
), claimed AS (
    UPDATE tenant_management.tenant_business_database_provisioning_task task
       SET status='LEASED',attempt_count=attempt_count+1,
           database_port=coalesce(database_port,$candidate_port),
           claim_token='$claim_token'::UUID,
           lease_until=current_timestamp + interval '2 minutes',next_attempt_at=current_timestamp,
           failure_code=NULL,completed_at=NULL,updated_at=current_timestamp,version=version+1
      FROM candidate
     WHERE task.task_id=candidate.task_id
    RETURNING task.task_id,task.tenant_id,task.workspace_id,task.database_port,task.claim_token
)
SELECT task_id::TEXT || '|' || tenant_id::TEXT || '|' || workspace_id::TEXT || '|' || database_port::TEXT
       || '|' || claim_token::TEXT
  FROM claimed;
SQL
}
ready_task_by_id() {
	local task_id="$1"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
SELECT task_id::TEXT || '|' || tenant_id::TEXT || '|' || workspace_id::TEXT || '|' || database_port::TEXT
  FROM tenant_management.tenant_business_database_provisioning_task
 WHERE task_id='$task_id'::UUID AND status='READY' AND database_port IS NOT NULL;
SQL
}
workspace_anchor_task_status_for_scope() {
	local tenant_id="$1" workspace_id="$2"
	provisioner_psql <<SQL
SET app.current_tenant_id = '$tenant_id';
SET app.current_workspace_id = '$workspace_id';
SELECT status FROM tenant_management.tenant_business_database_workspace_anchor_task
 WHERE tenant_id='$tenant_id'::UUID AND workspace_id='$workspace_id'::UUID;
SQL
}
verify_ready_task_local_state() {
	local task_id="$1" selected_task verified_task tenant_id workspace_id port anchor_status
	if ! selected_task="$(ready_task_by_id "$task_id")" || [[ -z "$selected_task" ]]; then
		printf 'Provisioning task %s is no longer centrally READY.\n' "$task_id" >&2
		return 1
	fi
	IFS='|' read -r _ tenant_id workspace_id port <<<"$selected_task"
	[[ "$tenant_id" =~ $UUID_PATTERN && "$workspace_id" =~ $UUID_PATTERN && "$port" =~ ^[0-9]{4,5}$ ]] || {
		printf 'Provisioning task %s returned invalid Tenant database scope.\n' "$task_id" >&2
		return 1
	}
	if ! anchor_status="$(workspace_anchor_task_status_for_scope "$tenant_id" "$workspace_id")" \
		|| [[ "$anchor_status" != READY ]]; then
		printf 'Provisioning task %s does not have a READY anchor for its exact Tenant and Workspace.\n' "$task_id" >&2
		return 1
	fi
	if ! "$PROVISIONER" "$tenant_id" "$workspace_id" "$port" --verify-ready-only \
		--provisioner-password-stdin --central-configfile "$CENTRAL_CONFIG_FILE" \
		--local-state-dir "$TENANT_STATE_DIRECTORY" 3< <(printf '%s\n' "$PROVISIONER_PASSWORD"); then
		printf 'Provisioning task %s is centrally READY, but its private Tenant state or volume failed read-only verification.\n' "$task_id" >&2
		return 1
	fi
	if ! verified_task="$(ready_task_by_id "$task_id")" || [[ "$verified_task" != "$selected_task" ]]; then
		printf 'Provisioning task %s changed while its private Tenant state was being verified.\n' "$task_id" >&2
		return 1
	fi
	if ! anchor_status="$(workspace_anchor_task_status_for_scope "$tenant_id" "$workspace_id")" \
		|| [[ "$anchor_status" != READY ]]; then
		printf 'Provisioning task %s lost its READY Tenant Workspace anchor during verification.\n' "$task_id" >&2
		return 1
	fi
	printf 'Provisioning task %s central READY state matches its private Tenant state and volume.\n' "$task_id"
}
claim_workspace_anchor_task_for_scope() {
	local tenant_id="$1" workspace_id="$2" claim_token="$3"
	provisioner_psql <<SQL
SET app.current_tenant_id = '$tenant_id';
SET app.current_workspace_id = '$workspace_id';
WITH candidate AS (
    SELECT anchor.task_id,anchor.tenant_id,anchor.workspace_id,provisioning.database_port
      FROM tenant_management.tenant_business_database_workspace_anchor_task anchor
      JOIN tenant_management.tenant_business_database_provisioning_task provisioning
        ON provisioning.tenant_id=anchor.tenant_id AND provisioning.status='READY'
     WHERE anchor.tenant_id='$tenant_id'::UUID AND anchor.workspace_id='$workspace_id'::UUID
       AND ((anchor.status IN ('PENDING','FAILED') AND anchor.next_attempt_at <= current_timestamp)
        OR (anchor.status='LEASED' AND anchor.lease_until <= current_timestamp))
       AND provisioning.database_port IS NOT NULL
     FOR UPDATE OF anchor SKIP LOCKED
     LIMIT 1
), claimed AS (
    UPDATE tenant_management.tenant_business_database_workspace_anchor_task anchor
       SET status='LEASED',attempt_count=attempt_count+1,claim_token='$claim_token'::UUID,
           lease_until=current_timestamp + interval '2 minutes',next_attempt_at=current_timestamp,
           failure_code=NULL,completed_at=NULL,updated_at=current_timestamp,version=version+1
      FROM candidate
     WHERE anchor.task_id=candidate.task_id
    RETURNING anchor.task_id,anchor.tenant_id,anchor.workspace_id,candidate.database_port,anchor.claim_token
)
SELECT task_id::TEXT || '|' || tenant_id::TEXT || '|' || workspace_id::TEXT || '|' || database_port::TEXT
       || '|' || claim_token::TEXT
  FROM claimed;
SQL
}
claim_next_workspace_anchor_task() {
	local claim_token="$1"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
WITH candidate AS (
    SELECT anchor.task_id,anchor.tenant_id,anchor.workspace_id,provisioning.database_port
      FROM tenant_management.tenant_business_database_workspace_anchor_task anchor
      JOIN tenant_management.tenant_business_database_provisioning_task provisioning
        ON provisioning.tenant_id=anchor.tenant_id AND provisioning.status='READY'
     WHERE ((anchor.status IN ('PENDING','FAILED') AND anchor.next_attempt_at <= current_timestamp)
        OR (anchor.status='LEASED' AND anchor.lease_until <= current_timestamp))
       AND provisioning.database_port IS NOT NULL
     ORDER BY anchor.created_at,anchor.task_id
     FOR UPDATE OF anchor SKIP LOCKED
     LIMIT 1
), claimed AS (
    UPDATE tenant_management.tenant_business_database_workspace_anchor_task anchor
       SET status='LEASED',attempt_count=attempt_count+1,claim_token='$claim_token'::UUID,
           lease_until=current_timestamp + interval '2 minutes',next_attempt_at=current_timestamp,
           failure_code=NULL,completed_at=NULL,updated_at=current_timestamp,version=version+1
      FROM candidate
     WHERE anchor.task_id=candidate.task_id
    RETURNING anchor.task_id,anchor.tenant_id,anchor.workspace_id,candidate.database_port,anchor.claim_token
)
SELECT task_id::TEXT || '|' || tenant_id::TEXT || '|' || workspace_id::TEXT || '|' || database_port::TEXT
       || '|' || claim_token::TEXT
  FROM claimed;
SQL
}
mark_workspace_anchor_ready() {
	local task_id="$1" claim_token="$2" tenant_id="$3"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
SET app.current_tenant_id = '$tenant_id';
UPDATE tenant_management.tenant_business_database_workspace_anchor_task anchor
   SET status='READY',claim_token=NULL,lease_until=NULL,failure_code=NULL,
       completed_at=current_timestamp,updated_at=current_timestamp,version=version+1
 WHERE anchor.task_id='$task_id'::UUID AND anchor.status='LEASED'
   AND anchor.claim_token='$claim_token'::UUID AND anchor.lease_until>current_timestamp
   AND EXISTS (
       SELECT 1 FROM tenant_management.tenant_business_database_binding binding
        WHERE binding.tenant_id=anchor.tenant_id AND binding.lifecycle_state='READY'
   )
   AND EXISTS (
       SELECT 1 FROM tenant_management.tenant_business_database_provisioning_task provisioning
        WHERE provisioning.tenant_id=anchor.tenant_id AND provisioning.status='READY'
   )
RETURNING task_id;
SQL
}
mark_workspace_anchor_failed() {
	local task_id="$1" claim_token="$2"
	provisioner_psql <<SQL
SET app.cross_scope_workspace_scan = 'true';
UPDATE tenant_management.tenant_business_database_workspace_anchor_task
   SET status='FAILED',claim_token=NULL,lease_until=NULL,failure_code='SCOPE_PROJECTION_FAILED',
       next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second'),
       completed_at=NULL,updated_at=current_timestamp,version=version+1
 WHERE task_id='$task_id'::UUID AND status='LEASED' AND claim_token='$claim_token'::UUID
   AND lease_until>current_timestamp
RETURNING task_id;
SQL
}
random_database_port() {
	local raw
	raw="$(od -An -N4 -tu4 /dev/urandom | tr -d '[:space:]')"
	printf '%s\n' "$((20000 + raw % 30000))"
}
stop_children() {
	STOPPING=1
	if [[ -n "$ACTIVE_CHILD" ]] && kill -0 "$ACTIVE_CHILD" 2>/dev/null; then
		kill -TERM "$ACTIVE_CHILD" 2>/dev/null || true
	fi
	if [[ -n "$HEARTBEAT_PID" ]] && kill -0 "$HEARTBEAT_PID" 2>/dev/null; then
		kill -TERM "$HEARTBEAT_PID" 2>/dev/null || true
	fi
	if [[ -n "$ACTIVE_CHILD" ]]; then wait "$ACTIVE_CHILD" 2>/dev/null || true; fi
	if [[ -n "$HEARTBEAT_PID" ]]; then wait "$HEARTBEAT_PID" 2>/dev/null || true; fi
	local saved_pid=''
	if [[ -f "$PID_FILE" ]]; then saved_pid="$(cat "$PID_FILE" 2>/dev/null || true)"; fi
	if [[ "$saved_pid" == "$$" ]]; then rm -f -- "$PID_FILE"; fi
	local ready_pid=''
	if [[ -f "$READY_FILE" ]]; then ready_pid="$(cat "$READY_FILE" 2>/dev/null || true)"; fi
	if [[ "$ready_pid" == "$$" ]]; then rm -f -- "$READY_FILE"; fi
	exit 0
}
stop_one_shot_children() {
	local exit_code="$1"
	STOPPING=1
	if [[ -n "$ACTIVE_CHILD" ]] && kill -0 "$ACTIVE_CHILD" 2>/dev/null; then
		kill -TERM "$ACTIVE_CHILD" 2>/dev/null || true
	fi
	if [[ -n "$HEARTBEAT_PID" ]] && kill -0 "$HEARTBEAT_PID" 2>/dev/null; then
		kill -TERM "$HEARTBEAT_PID" 2>/dev/null || true
	fi
	if [[ -n "$ACTIVE_CHILD" ]]; then wait "$ACTIVE_CHILD" 2>/dev/null || true; fi
	if [[ -n "$HEARTBEAT_PID" ]]; then wait "$HEARTBEAT_PID" 2>/dev/null || true; fi
	exit "$exit_code"
}
heartbeat_task() {
	local task_id="$1" claim_token="$2" child_pid="$3"
	local renewed_task_id=''
	while kill -0 "$child_pid" 2>/dev/null && [[ "$STOPPING" == 0 ]]; do
		sleep 20
		kill -0 "$child_pid" 2>/dev/null || return 0
		if renewed_task_id="$(current_task_claimed "$task_id" "$claim_token" 120 2>/dev/null)" \
			&& [[ "$renewed_task_id" == "$task_id" ]]; then continue; fi
		printf '%s\n' 'Provisioning lease ownership was lost; stopping the stale provisioner.' >&2
		kill -TERM "$child_pid" 2>/dev/null || true
		return 1
	done
}
heartbeat_workspace_anchor_task() {
	local task_id="$1" claim_token="$2" child_pid="$3"
	local renewed_task_id=''
	while kill -0 "$child_pid" 2>/dev/null && [[ "$STOPPING" == 0 ]]; do
		sleep 20
		kill -0 "$child_pid" 2>/dev/null || return 0
		if renewed_task_id="$(current_workspace_anchor_claimed "$task_id" "$claim_token" 120 2>/dev/null)" \
			&& [[ "$renewed_task_id" == "$task_id" ]]; then continue; fi
		printf '%s\n' 'Workspace anchor lease ownership was lost; stopping the stale scope writer.' >&2
		kill -TERM "$child_pid" 2>/dev/null || true
		return 1
	done
}
process_task() {
	local claim="$1" task_id tenant_id workspace_id port claim_token provision_status ready_result
	IFS='|' read -r task_id tenant_id workspace_id port claim_token <<<"$claim"
	[[ "$task_id" =~ ^[0-9a-f-]{36}$ && "$tenant_id" =~ ^[0-9a-f-]{36}$ \
		&& "$workspace_id" =~ ^[0-9a-f-]{36}$ && "$port" =~ ^[0-9]{4,5}$ \
		&& "$claim_token" =~ ^[0-9a-f-]{36}$ ]] || {
		printf '%s\n' 'Provisioning task claim returned malformed scope data.' >&2
		return 1
	}
	if ! current_task_claimed "$task_id" "$claim_token" 120 >/dev/null; then
		return 1
	fi
	printf 'Provisioning Tenant database task %s.\n' "$task_id"
	"$PROVISIONER" "$tenant_id" "$workspace_id" "$port" --retry-failed --provisioner-password-stdin \
		--task-id "$task_id" --claim-token "$claim_token" \
		--central-configfile "$CENTRAL_CONFIG_FILE" --local-state-dir "$TENANT_STATE_DIRECTORY" \
		3< <(printf '%s\n' "$PROVISIONER_PASSWORD") &
	ACTIVE_CHILD=$!
	heartbeat_task "$task_id" "$claim_token" "$ACTIVE_CHILD" &
	HEARTBEAT_PID=$!
	if wait "$ACTIVE_CHILD"; then provision_status=0; else provision_status=$?; fi
	ACTIVE_CHILD=''
	kill -TERM "$HEARTBEAT_PID" 2>/dev/null || true
	wait "$HEARTBEAT_PID" 2>/dev/null || true
	HEARTBEAT_PID=''
	if [[ "$provision_status" -eq 0 ]]; then
		ready_result="$(mark_task_ready "$task_id" "$claim_token" "$tenant_id" || true)"
		if [[ "$ready_result" == "$task_id" ]]; then
			printf 'Provisioning task %s reached READY.\n' "$task_id"
			return 0
		fi
		printf 'Provisioning task %s lost its final READY compare-and-set.\n' "$task_id" >&2
		return 1
	fi
	mark_task_failed "$task_id" "$claim_token" >/dev/null || true
	printf 'Provisioning task %s failed; it remains retryable after backoff.\n' "$task_id" >&2
	return 1
}
process_workspace_anchor_task() {
	local claim="$1" task_id tenant_id workspace_id port claim_token provision_status ready_result
	IFS='|' read -r task_id tenant_id workspace_id port claim_token <<<"$claim"
	[[ "$task_id" =~ ^[0-9a-f-]{36}$ && "$tenant_id" =~ ^[0-9a-f-]{36}$ \
		&& "$workspace_id" =~ ^[0-9a-f-]{36}$ && "$port" =~ ^[0-9]{4,5}$ \
		&& "$claim_token" =~ ^[0-9a-f-]{36}$ ]] || {
		printf '%s\n' 'Workspace anchor claim returned malformed scope data.' >&2
		return 1
	}
	if ! current_workspace_anchor_claimed "$task_id" "$claim_token" 120 >/dev/null; then return 1; fi
	printf 'Projecting a Workspace scope anchor for Tenant %s.\n' "$tenant_id"
	"$PROVISIONER" "$tenant_id" "$workspace_id" "$port" --workspace-anchor-only --provisioner-password-stdin \
		--workspace-task-id "$task_id" --claim-token "$claim_token" \
		--central-configfile "$CENTRAL_CONFIG_FILE" --local-state-dir "$TENANT_STATE_DIRECTORY" \
		3< <(printf '%s\n' "$PROVISIONER_PASSWORD") &
	ACTIVE_CHILD=$!
	heartbeat_workspace_anchor_task "$task_id" "$claim_token" "$ACTIVE_CHILD" &
	HEARTBEAT_PID=$!
	if wait "$ACTIVE_CHILD"; then provision_status=0; else provision_status=$?; fi
	ACTIVE_CHILD=''
	kill -TERM "$HEARTBEAT_PID" 2>/dev/null || true
	wait "$HEARTBEAT_PID" 2>/dev/null || true
	HEARTBEAT_PID=''
	if [[ "$provision_status" -eq 0 ]]; then
		ready_result="$(mark_workspace_anchor_ready "$task_id" "$claim_token" "$tenant_id" || true)"
		if [[ "$ready_result" == "$task_id" ]]; then
			printf 'Workspace anchor task %s reached READY without changing Tenant binding state.\n' "$task_id"
			return 0
		fi
		printf 'Workspace anchor task %s lost its final READY compare-and-set.\n' "$task_id" >&2
		return 1
	fi
	if [[ "$provision_status" -eq 75 ]]; then
		printf 'Workspace anchor task %s deferred until the Tenant schema upgrade finishes.\n' "$task_id"
		return 0
	fi
	mark_workspace_anchor_failed "$task_id" "$claim_token" >/dev/null || true
	printf 'Workspace anchor task %s failed; it remains retryable after backoff.\n' "$task_id" >&2
}


if [[ "$ONE_SHOT" == 1 ]]; then
	validate_one_shot_state
	load_provisioner_password
	command -v docker >/dev/null 2>&1 || fail 'Docker is required for one-shot Tenant provisioning.'
	command -v openssl >/dev/null 2>&1 || fail 'OpenSSL is required to generate local credentials.'
	docker_context="$(docker context show 2>/dev/null)" || fail 'The local Docker context could not be identified.'
	docker_endpoint="$(docker context inspect "$docker_context" --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" \
		|| fail 'The local Docker endpoint could not be verified.'
	case "$docker_endpoint" in
		unix://*|npipe://*) ;;
		*) fail 'One-shot Tenant provisioning requires a local Docker socket context.' ;;
	esac
	export DOCKER_CONTEXT="$docker_context"
	docker info >/dev/null 2>&1 || fail 'The local Docker engine is unavailable.'
	central_inspection="$(docker inspect --format '{{.Id}}|{{.State.Status}}|{{.Config.Image}}|{{if index .Config.Labels "com.docker.compose.project"}}{{index .Config.Labels "com.docker.compose.project"}}{{end}}|{{if index .Config.Labels "com.docker.compose.service"}}{{index .Config.Labels "com.docker.compose.service"}}{{end}}|{{if index .Config.Labels "com.nexa.local-provisioning.disposable-test"}}{{index .Config.Labels "com.nexa.local-provisioning.disposable-test"}}{{end}}' "$CENTRAL_CONTAINER_ID" 2>/dev/null)" \
		|| fail 'The requested central PostgreSQL container could not be inspected in the local Docker context.'
	IFS='|' read -r inspected_id inspected_state inspected_image compose_project compose_service disposable_marker <<<"$central_inspection"
	[[ "$inspected_id" == "$CENTRAL_CONTAINER_ID" && "$inspected_state" == running ]] \
		|| fail 'The requested central container must be the matching running local container.'
	case "$inspected_image" in
		postgres:*|docker.io/library/postgres:*|docker.io/postgres:*) ;;
		*) fail 'The requested central container must use the official PostgreSQL image.' ;;
	esac
	[[ "$disposable_marker" == true ]] \
		|| fail 'One-shot Tenant provisioning requires an explicitly disposable PostgreSQL container.'
	[[ -z "$compose_project$compose_service" ]] \
		|| fail 'One-shot Tenant provisioning refuses Compose-managed PostgreSQL containers.'
	docker exec "$CENTRAL_CONTAINER_ID" psql --version >/dev/null 2>&1 \
		|| fail 'The requested central PostgreSQL container does not provide psql.'
else
	[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail 'The private Modern local environment is unavailable.'
	private_mode "$ENV_FILE" 600 || fail 'The Modern local environment must be owner-only.'
	load_provisioner_password
	[[ ! -L "$API_DIRECTORY/.local" ]] || fail 'Local worker state cannot be a symbolic link.'
	mkdir -p -- "$API_DIRECTORY/.local" "$STATE_DIRECTORY"
	chmod 700 "$API_DIRECTORY/.local" "$STATE_DIRECTORY"
	private_mode "$API_DIRECTORY/.local" 700 && private_mode "$STATE_DIRECTORY" 700 \
		|| fail 'Local provisioning worker state must be owner-only.'
	[[ ! -L "$TENANT_STATE_DIRECTORY" ]] || fail 'Tenant credential state cannot be a symbolic link.'
	if [[ -d "$TENANT_STATE_DIRECTORY" ]]; then
		private_mode "$TENANT_STATE_DIRECTORY" 700 || fail 'Tenant credential state must be owner-only.'
	fi

	central_container_id="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps -q modern-postgres)"
	[[ "$central_container_id" =~ ^[0-9a-f]{64}$ ]] || fail 'The local central PostgreSQL container is unavailable.'
	central_config_temporary="$CENTRAL_CONFIG_FILE.$$.tmp"
	printf 'container-id=%s\n' "$central_container_id" > "$central_config_temporary"
	chmod 600 "$central_config_temporary"
	mv -- "$central_config_temporary" "$CENTRAL_CONFIG_FILE"
fi

schema_ready=''
for _ in $(seq 1 60); do
	if schema_ready="$(provisioner_psql <<'SQL'
SELECT 'ready'
 WHERE current_user='nexa_tenant_database_provisioner'
   AND has_table_privilege(current_user,
       'tenant_management.tenant_business_database_provisioning_task','SELECT')
   AND has_column_privilege(current_user,
       'tenant_management.tenant_business_database_provisioning_task','status','UPDATE')
   AND has_table_privilege(current_user,
       'tenant_management.tenant_business_database_workspace_anchor_task','SELECT')
   AND has_column_privilege(current_user,
       'tenant_management.tenant_business_database_workspace_anchor_task','status','UPDATE');
SQL
	)" && [[ "$schema_ready" == ready ]]; then
		break
	fi
	sleep 1
done
[[ "$schema_ready" == ready ]] || fail 'Central Tenant database provisioning queues are not ready.'
if [[ "$ONE_SHOT" == 1 ]]; then
	trap 'stop_one_shot_children 143' TERM
	trap 'stop_one_shot_children 130' INT
	claim_token="$(new_uuid)"
	port="$(random_database_port)"
	if ! claim="$(claim_task_by_id "$ONE_SHOT_TASK_ID" "$port" "$claim_token")"; then
		fail "Provisioning task $ONE_SHOT_TASK_ID could not be claimed from its UUID-scoped queue entry."
	fi
	if [[ -n "$claim" ]]; then
		IFS='|' read -r _provisioning_task_id tenant_id _workspace_id _database_port _provisioning_claim_token <<< "$claim"
		if [[ "$ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR" == 1 ]]; then
			validate_one_shot_tenant_state "$tenant_id"
		fi
		process_task "$claim" || exit $?
	elif [[ "$ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR" == 1 ]]; then
		claim="$(ready_task_by_id "$ONE_SHOT_TASK_ID")" \
			|| fail "Provisioning task $ONE_SHOT_TASK_ID READY state could not be inspected."
		[[ -n "$claim" ]] || { printf 'Provisioning task %s is not currently claimable.\n' "$ONE_SHOT_TASK_ID" >&2; exit 3; }
		IFS='|' read -r _provisioning_task_id tenant_id _workspace_id _database_port <<< "$claim"
		validate_one_shot_tenant_state "$tenant_id"
		printf 'Provisioning task %s is already READY; skipping Tenant database reprovisioning.\n' "$ONE_SHOT_TASK_ID"
	else
		printf 'Provisioning task %s is not currently claimable.\n' "$ONE_SHOT_TASK_ID" >&2
		exit 3
	fi
	if [[ "$ONE_SHOT_INCLUDE_WORKSPACE_ANCHOR" == 1 ]]; then
		IFS='|' read -r _provisioning_task_id tenant_id workspace_id _database_port _provisioning_claim_token <<< "$claim"
		anchor_status="$(workspace_anchor_task_status_for_scope "$tenant_id" "$workspace_id")" \
			|| fail 'The selected Tenant Workspace anchor task could not be inspected.'
		[[ -n "$anchor_status" ]] || fail 'The selected Tenant has no Workspace anchor task.'
		if [[ "$anchor_status" != READY ]]; then
			anchor_claim_token="$(new_uuid)"
			anchor_claim="$(claim_workspace_anchor_task_for_scope "$tenant_id" "$workspace_id" "$anchor_claim_token")" \
				|| fail 'The selected Tenant Workspace anchor task could not be claimed.'
			[[ -n "$anchor_claim" ]] || { printf 'Workspace anchor task for Tenant %s and Workspace %s is not currently claimable (status %s).\n' "$tenant_id" "$workspace_id" "$anchor_status" >&2; exit 3; }
			process_workspace_anchor_task "$anchor_claim" || exit $?
			anchor_status="$(workspace_anchor_task_status_for_scope "$tenant_id" "$workspace_id")" \
				|| fail 'The selected Tenant Workspace anchor readiness could not be verified.'
			[[ "$anchor_status" == READY ]] || fail 'The selected Tenant Workspace anchor did not reach READY.'
		fi
		verify_ready_task_local_state "$ONE_SHOT_TASK_ID" \
			|| fail 'The selected centrally READY Tenant database and Workspace anchor could not be verified locally.'
	fi
	exit 0
fi
if [[ "$RECONCILE_SCHEMA_MANIFEST" == 1 ]]; then
	reconcile_stale_ready_tasks || exit 1
	exit 0
fi
if ! reconcile_stale_ready_tasks; then
	printf '%s\n' 'Tenant schema reconciliation failed; stale Tenant routes remain fail-closed.' >&2
fi
[[ ! -L "$READY_FILE" ]] || fail 'Local provisioning worker readiness state cannot be a symbolic link.'
rm -f -- "$READY_FILE"
ready_temporary="$READY_FILE.$$.tmp"
printf '%s\n' "$$" > "$ready_temporary"
chmod 600 "$ready_temporary"
mv -- "$ready_temporary" "$READY_FILE"

trap stop_children TERM INT
while [[ "$STOPPING" == 0 ]]; do
	claim_token="$(new_uuid)"
	port="$(random_database_port)"
	if claim="$(claim_next_task "$port" "$claim_token" 2>/dev/null)" && [[ -n "$claim" ]]; then
		process_task "$claim" || true
		continue
	fi
	claim_token="$(new_uuid)"
	if workspace_anchor_claim="$(claim_next_workspace_anchor_task "$claim_token" 2>/dev/null)" \
		&& [[ -n "$workspace_anchor_claim" ]]; then
		process_workspace_anchor_task "$workspace_anchor_claim" || true
	else
		sleep 5
	fi
done
