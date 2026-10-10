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

fail() { printf '%s\n' "$1" >&2; exit 1; }
private_mode() {
	local path="$1" expected="$2" actual
	[[ ! -L "$path" ]] || return 1
	actual="$(stat -f '%Lp' "$path" 2>/dev/null || stat -c '%a' "$path" 2>/dev/null)" || return 1
	[[ "$actual" == "$expected" ]]
}
new_uuid() {
	local value
	value="$(openssl rand -hex 16)"
	printf '%s-%s-%s-%s-%s\n' "${value:0:8}" "${value:8:4}" "${value:12:4}" "${value:16:4}" "${value:20:12}"
}
runtime_psql() {
	docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T modern-postgres sh -ec '
		export PGPASSWORD="$POSTGRES_PASSWORD"
		exec psql -X -qAt -v ON_ERROR_STOP=1 -U nexa_runtime -d "$POSTGRES_DB"
	' 2>/dev/null
}
current_task_claimed() {
	local task_id="$1" claim_token="$2" lease_seconds="$3"
	runtime_psql <<SQL
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
	runtime_psql <<SQL
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
	runtime_psql <<SQL
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
	runtime_psql <<SQL
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
	runtime_psql <<SQL
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
claim_next_workspace_anchor_task() {
	local claim_token="$1"
	runtime_psql <<SQL
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
	runtime_psql <<SQL
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
	runtime_psql <<SQL
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
	"$PROVISIONER" "$tenant_id" "$workspace_id" "$port" --retry-failed \
		--task-id "$task_id" --claim-token "$claim_token" \
		--central-configfile "$CENTRAL_CONFIG_FILE" --local-state-dir "$TENANT_STATE_DIRECTORY" &
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
	"$PROVISIONER" "$tenant_id" "$workspace_id" "$port" --workspace-anchor-only \
		--workspace-task-id "$task_id" --claim-token "$claim_token" \
		--central-configfile "$CENTRAL_CONFIG_FILE" --local-state-dir "$TENANT_STATE_DIRECTORY" &
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

[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail 'The private Modern local environment is unavailable.'
private_mode "$ENV_FILE" 600 || fail 'The Modern local environment must be owner-only.'
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

[[ ! -L "$READY_FILE" ]] || fail 'Local provisioning worker readiness state cannot be a symbolic link.'
rm -f -- "$READY_FILE"
schema_ready=''
for _ in $(seq 1 60); do
	if schema_ready="$(runtime_psql <<'SQL'
SELECT 'ready'
 WHERE to_regclass('tenant_management.tenant_business_database_provisioning_task') IS NOT NULL
   AND to_regclass('tenant_management.tenant_business_database_workspace_anchor_task') IS NOT NULL;
SQL
	)" && [[ "$schema_ready" == ready ]]; then
		break
	fi
	sleep 1
done
[[ "$schema_ready" == ready ]] || fail 'Central Tenant database provisioning queues are not ready.'
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
