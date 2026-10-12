#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

API_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd -- "$API_DIRECTORY"

fail() { printf '%s\n' "$1" >&2; exit 1; }
mask_secret() { printf '::add-mask::%s\n' "$1"; }
random_secret() { openssl rand -hex 32; }

[[ "${GITHUB_ACTIONS:-}" == true ]] || fail 'This fixture preparation helper is restricted to GitHub Actions.'
[[ -n "${GITHUB_WORKSPACE:-}" && "$(cd -- "$GITHUB_WORKSPACE" && pwd)" == "$API_DIRECTORY" ]] \
	|| fail 'GitHub Actions workspace must be this API checkout.'
[[ -f "${GITHUB_ENV:-}" && ! -L "$GITHUB_ENV" ]] || fail 'GitHub Actions environment file is unavailable.'
BASE_URL=http://127.0.0.1:8080
for path in .env.local .local-keys .local; do
	[[ ! -e "$path" && ! -L "$path" ]] || fail "Refusing to reuse existing CI-local state at $path."
done

postgres_password="$(random_secret)"
documents_reader_password="$(random_secret)"
reset_throttle_key="$(random_secret)"
notification_outbox_key="$(random_secret)"
system_operator_token="$(random_secret)"
minio_root_user="ci$(openssl rand -hex 8)"
minio_root_password="$(random_secret)"
owner_password="$(random_secret)"
tenant_admin_password="$(random_secret)"
sales_password="$(random_secret)"
warehouse_password="$(random_secret)"
logistics_password="$(random_secret)"
buyer_password="$(random_secret)"

for secret in \
	"$postgres_password" "$documents_reader_password" "$reset_throttle_key" \
	"$notification_outbox_key" "$system_operator_token" "$minio_root_user" "$minio_root_password" \
	"$owner_password" "$tenant_admin_password" "$sales_password" \
	"$warehouse_password" "$logistics_password" "$buyer_password"; do
	mask_secret "$secret"
done

mkdir -m 750 .local-keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local-keys/access-token-private.pem >/dev/null 2>&1
openssl pkey -in .local-keys/access-token-private.pem -pubout -out .local-keys/access-token-public.pem >/dev/null 2>&1
# The API image runs as UID 10001 and belongs to GID 1000; keep signing keys
# private to that runtime group and the owner of the ephemeral CI checkout.
if command -v sudo >/dev/null 2>&1; then
	sudo chgrp 1000 .local-keys/access-token-private.pem .local-keys/access-token-public.pem
else
	chgrp 1000 .local-keys/access-token-private.pem .local-keys/access-token-public.pem
fi
if command -v sudo >/dev/null 2>&1; then
	sudo chgrp 1000 .local-keys
else
	chgrp 1000 .local-keys
fi
chmod 750 .local-keys
chmod 640 .local-keys/access-token-private.pem .local-keys/access-token-public.pem

cat > .env.local <<EOF
NEXA_DATABASE_URL=jdbc:postgresql://localhost:5432/nexa
NEXA_DATABASE_USERNAME=nexa_runtime
NEXA_DATABASE_PASSWORD=$postgres_password
NEXA_MODERN_POSTGRES_DB=nexa
NEXA_MODERN_POSTGRES_USER=nexa
NEXA_MODERN_POSTGRES_PASSWORD=$postgres_password
NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD=$documents_reader_password
NEXA_MINIO_ROOT_USER=$minio_root_user
NEXA_MINIO_ROOT_PASSWORD=$minio_root_password
NEXA_OBJECT_STORAGE_BUCKET=nexa-private
NEXA_MODERN_SPRING_PROFILE=local,minio
NEXA_SECURITY_ISSUER=http://127.0.0.1:8080
NEXA_SECURITY_AUDIENCE=nexa-local
NEXA_PASSWORD_RESET_THROTTLE_KEY=$reset_throttle_key
NEXA_NOTIFICATION_OUTBOX_KEY=$notification_outbox_key
NEXA_SYSTEM_OPERATOR_TOKEN=$system_operator_token
NEXA_SECURITY_RSA_PUBLIC_KEY=./.local-keys/access-token-public.pem
NEXA_SECURITY_RSA_PRIVATE_KEY=./.local-keys/access-token-private.pem
NEXA_BCRYPT_STRENGTH=12
NEXA_DEV_BOOTSTRAP_ENABLED=true
NEXA_DEV_TENANT_NAME=ICISA
NEXA_DEV_TENANT_SLUG=icisa
NEXA_DEV_WORKSPACE_NAME=ICISA
NEXA_DEV_WORKSPACE_SLUG=icisa
NEXA_DEV_OWNER_EMAIL=owner@icisa.test
NEXA_DEV_OWNER_PASSWORD=$owner_password
NEXA_DEV_TENANT_ADMIN_EMAIL=tenant-admin@icisa.test
NEXA_DEV_TENANT_ADMIN_PASSWORD=$tenant_admin_password
NEXA_DEV_SALES_EMAIL=sales@icisa.test
NEXA_DEV_SALES_PASSWORD=$sales_password
NEXA_DEV_WAREHOUSE_EMAIL=warehouse@icisa.test
NEXA_DEV_WAREHOUSE_PASSWORD=$warehouse_password
NEXA_DEV_LOGISTICS_EMAIL=logistics@icisa.test
NEXA_DEV_LOGISTICS_PASSWORD=$logistics_password
NEXA_DEV_BUYER_EMAIL=buyer@icisa.test
NEXA_DEV_BUYER_PASSWORD=$buyer_password
NEXA_ALLOWED_ORIGINS=http://localhost:4200,http://localhost:4300
NEXA_SMTP_HOST=modern-mailpit
NEXA_SMTP_REQUIRED=false
NEXA_OTEL_METRICS_ENABLED=false
NEXA_PAYMENTS_PROVIDER=deterministic
NEXA_CLAMAV_HOST=modern-clamav
EOF
chmod 600 .env.local

{
	printf 'NEXA_SECURITY_LOAD_COMPOSE_STARTED=true\n'
	printf 'BASE_URL=%s\n' "$BASE_URL"
	printf 'NEXA_DEV_WORKSPACE=icisa\nNEXA_LOAD_WORKSPACE=icisa\n'
	printf 'NEXA_DEV_OWNER_EMAIL=owner@icisa.test\nNEXA_LOAD_OWNER_EMAIL=owner@icisa.test\n'
	printf 'NEXA_DEV_OWNER_PASSWORD=%s\nNEXA_LOAD_OWNER_PASSWORD=%s\n' "$owner_password" "$owner_password"
	printf 'NEXA_DEV_TENANT_ADMIN_EMAIL=tenant-admin@icisa.test\n'
	printf 'NEXA_DEV_TENANT_ADMIN_PASSWORD=%s\n' "$tenant_admin_password"
	printf 'NEXA_DEV_SALES_EMAIL=sales@icisa.test\nNEXA_LOAD_SALES_EMAIL=sales@icisa.test\n'
	printf 'NEXA_DEV_SALES_PASSWORD=%s\nNEXA_LOAD_SALES_PASSWORD=%s\n' "$sales_password" "$sales_password"
	printf 'NEXA_DEV_WAREHOUSE_EMAIL=warehouse@icisa.test\nNEXA_LOAD_WAREHOUSE_EMAIL=warehouse@icisa.test\n'
	printf 'NEXA_DEV_WAREHOUSE_PASSWORD=%s\nNEXA_LOAD_WAREHOUSE_PASSWORD=%s\n' "$warehouse_password" "$warehouse_password"
	printf 'NEXA_DEV_LOGISTICS_EMAIL=logistics@icisa.test\nNEXA_LOAD_LOGISTICS_EMAIL=logistics@icisa.test\n'
	printf 'NEXA_DEV_LOGISTICS_PASSWORD=%s\nNEXA_LOAD_LOGISTICS_PASSWORD=%s\n' "$logistics_password" "$logistics_password"
	printf 'NEXA_DEV_BUYER_EMAIL=buyer@icisa.test\nNEXA_LOAD_BUYER_EMAIL=buyer@icisa.test\n'
	printf 'NEXA_DEV_BUYER_PASSWORD=%s\nNEXA_LOAD_BUYER_PASSWORD=%s\n' "$buyer_password" "$buyer_password"
	printf 'NEXA_OBJECT_STORAGE_ENDPOINT=http://127.0.0.1:9000\nNEXA_OBJECT_STORAGE_BUCKET=nexa-private\n'
} >> "$GITHUB_ENV"

./ops/compose/scripts/modern-up.sh api-runtime

api_ready=0
for _ in $(seq 1 300); do
	if curl --fail --silent --show-error --max-time 3 "$BASE_URL/actuator/health/readiness" >/dev/null 2>&1; then
		api_ready=1
		break
	fi
	sleep 2
done
[[ "$api_ready" == 1 ]] || fail 'Compose API did not reach readiness within 10 minutes.'

compose_file=ops/compose/modern.compose.yml
compose() { docker compose --env-file .env.local -f "$compose_file" "$@"; }
central_psql() {
	compose exec -T -e "PGPASSWORD=$postgres_password" modern-postgres psql \
		-X -qAt -v ON_ERROR_STOP=1 -U nexa_runtime -d nexa "$@"
}

identity=''
for _ in $(seq 1 60); do
	if identity="$(central_psql -v tenant_slug=icisa -v workspace_slug=icisa \
		-v buyer_email=buyer@icisa.test <<'SQL'
SET app.bootstrap_tenant_slug = :'tenant_slug';
SET app.workspace_lookup_slug = :'workspace_slug';
SELECT count(*)::text || '|' || min(tenant.id::text) || '|' || min(workspace.id::text)
       || '|' || min(buyer.id::text) || '|' || min(membership.id::text)
  FROM tenant_management.tenant tenant
  JOIN tenant_management.workspace workspace ON workspace.tenant_id=tenant.id
  JOIN tenant_management.workspace_membership membership ON membership.workspace_id=workspace.id
  JOIN iam.user_account buyer ON buyer.id=membership.user_id
 WHERE tenant.slug=:'tenant_slug' AND tenant.status='ACTIVE'
   AND workspace.slug=:'workspace_slug' AND workspace.status='ACTIVE'
   AND buyer.normalized_email=lower(:'buyer_email') AND buyer.status='ACTIVE'
   AND membership.membership_type='BUYER' AND membership.status='ACTIVE';
SQL
	)"; then
		IFS='|' read -r identity_count tenant_id workspace_id buyer_user_id buyer_membership_id <<< "$identity"
		if [[ "$identity_count" == 1 ]]; then break; fi
		[[ "$identity_count" == 0 ]] || fail 'Local bootstrap resolved more than one active scoped Buyer identity.'
	fi
	identity=''
	sleep 2
done
[[ -n "$identity" && "${identity_count:-}" == 1 ]] || fail 'Local bootstrap Tenant, Workspace, and Buyer identity did not become available.'
for value in "$tenant_id" "$workspace_id" "$buyer_user_id" "$buyer_membership_id"; do
	[[ "$value" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
		|| fail 'Local bootstrap returned an invalid scoped fixture identity.'
done
printf '%s\n' 'Resolved one active local Tenant, Workspace, and Buyer membership.'

provision_state=''
for _ in $(seq 1 270); do
	if provision_state="$(central_psql -v tenant_id="$tenant_id" -v workspace_id="$workspace_id" <<'SQL' 2>/dev/null
SET app.current_tenant_id = :'tenant_id';
SET app.current_workspace_id = :'workspace_id';
SELECT coalesce(binding.lifecycle_state, 'MISSING') || '|'
       || coalesce(provisioning.status, 'MISSING') || '|'
       || coalesce(anchor.status, 'MISSING')
  FROM (SELECT :'tenant_id'::UUID AS tenant_id, :'workspace_id'::UUID AS workspace_id) scope
  LEFT JOIN tenant_management.tenant_business_database_binding binding
    ON binding.tenant_id=scope.tenant_id
  LEFT JOIN tenant_management.tenant_business_database_provisioning_task provisioning
    ON provisioning.tenant_id=scope.tenant_id AND provisioning.workspace_id=scope.workspace_id
  LEFT JOIN tenant_management.tenant_business_database_workspace_anchor_task anchor
    ON anchor.tenant_id=scope.tenant_id AND anchor.workspace_id=scope.workspace_id;
SQL
	)"; then
		if [[ "$provision_state" == 'READY|READY|READY' ]]; then break; fi
	else
		provision_state='central-query-unavailable'
	fi
	if (( _ % 6 == 0 )); then
		printf 'Waiting for Tenant provisioning and Workspace anchor; current state: %s\n' "$provision_state"
	fi
	sleep 10
done
[[ "$provision_state" == 'READY|READY|READY' ]] \
	|| fail "Tenant binding, provisioning task, and Workspace anchor did not all reach READY within 45 minutes (last state: ${provision_state:-unknown})."

maven_image="$(sed -n '1{s/^FROM //;s/ AS build$//;p;}' Dockerfile)"
[[ "$maven_image" =~ @sha256:[0-9a-f]{64}$ ]] || fail 'API Dockerfile does not pin its Maven image by digest.'
mkdir -p -- "$HOME/.m2"
docker run --rm --network container:nexa-modern-api --user "$(id -u):$(id -g)" \
	--env-file "$API_DIRECTORY/.env.local" \
	-e NEXA_DATABASE_URL=jdbc:postgresql://modern-postgres:5432/nexa \
	-e NEXA_DATABASE_USERNAME=nexa_runtime \
	-e "NEXA_DATABASE_PASSWORD=$postgres_password" \
	-e NEXA_DATABASE_RUNTIME_USERNAME=nexa_runtime \
	-e "NEXA_DATABASE_RUNTIME_PASSWORD=$postgres_password" \
	-e NEXA_DATABASE_REQUIRE_LEAST_PRIVILEGE_RUNTIME=true \
	-e NEXA_FLYWAY_ENABLED=false \
	-e NEXA_TENANT_BUSINESS_LOCAL_CREDENTIALS_DIRECTORY=/workspace/.local/tenant-databases \
	-e MAVEN_USER_HOME=/maven-home/.m2 \
	-e MAVEN_OPTS=-Dmaven.repo.local=/maven-home/.m2/repository \
	-v "$HOME/.m2:/maven-home/.m2" \
	-v "$API_DIRECTORY:/workspace" -w /workspace \
	"$maven_image" ./ops/scripts/seed-local-tenant-business-fixtures.sh \
	"$tenant_id" "$workspace_id" "$buyer_user_id" "$buyer_membership_id" CLI-001

tenant_project="nexa-tenant-db-$tenant_id"
tenant_container="$(docker ps --filter "label=com.docker.compose.project=$tenant_project" \
	--filter label=com.docker.compose.service=tenant-database --format '{{.ID}}')"
[[ -n "$tenant_container" && "$tenant_container" != *$'\n'* ]] \
	|| fail 'The provisioned Tenant database container could not be identified uniquely.'
tenant_sql="SET app.current_tenant_id = '$tenant_id'; SET app.current_workspace_id = '$workspace_id'; SELECT string_agg(warehouse.id::text, ',' ORDER BY warehouse.id) FROM warehouse.warehouse warehouse WHERE warehouse.tenant_id = '$tenant_id'::UUID AND warehouse.workspace_id = '$workspace_id'::UUID AND warehouse.code = 'ICISA-COLD-01' AND warehouse.status = 'ACTIVE';"
warehouse_ids="$(docker exec "$tenant_container" sh -ec \
	'export PGPASSWORD="$(cat /run/secrets/bootstrap_admin_password)"; exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' \
	sh "$tenant_sql")"
[[ -n "$warehouse_ids" ]] || fail 'Tenant database fixture seed did not create an active ICISA-COLD-01 Warehouse.'
IFS=',' read -r -a warehouse_id_list <<< "$warehouse_ids"
(( ${#warehouse_id_list[@]} > 0 )) || fail 'Tenant database returned no Warehouse identifiers.'
for value in "${warehouse_id_list[@]}"; do
	[[ "$value" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
		|| fail 'Tenant database returned an invalid Warehouse identifier.'
done
printf 'NEXA_LOAD_WAREHOUSE_IDS=%s\n' "$warehouse_ids" >> "$GITHUB_ENV"
printf '%s\n' 'Security-load fixture seed and Tenant database Warehouse resolution completed.'
