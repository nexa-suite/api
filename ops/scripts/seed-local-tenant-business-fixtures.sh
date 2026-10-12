#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }
usage() {
	printf '%s\n' 'Usage: ops/scripts/seed-local-tenant-business-fixtures.sh <tenant-uuid> <workspace-uuid> <buyer-user-uuid> <buyer-membership-uuid> <buyer-client-code>'
	printf '%s\n' 'Seeds reviewed local BC02/03/05 business fixtures into one already-provisioned Tenant database.'
	printf '%s\n' 'Requires existing local API configuration and Tenant database credentials.'
}

if [[ $# -ne 5 ]]; then usage >&2; exit 2; fi
for value in "${@:1:4}"; do
	[[ "$value" =~ ^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$ ]] || fail 'All scope arguments must be UUIDs.'
done
[[ "$5" =~ ^[A-Z0-9][A-Z0-9-]{0,31}$ ]] || fail 'Buyer client code must use reviewed seed-code format.'

tenant_id="$1"
workspace_id="$2"
buyer_user_id="$3"
buyer_membership_id="$4"
buyer_client_code="$5"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

exec ./mvnw -DskipTests \
	-Dspring-boot.run.main-class=com.nexa.api.LocalTenantBusinessFixtureCli \
	"-Dspring-boot.run.arguments=--nexa.local-demo-fixtures.enabled=true --nexa.tenant-business.local-fixtures.enabled=true --nexa.tenant-business.notifications.enabled=false --nexa.tenant-business.payments.enabled=false --nexa.tenant-business.business-documents.enabled=false --nexa.tenant-business.business-traceability.enabled=false --nexa.local-demo-fixtures.tenant-id=${tenant_id} --nexa.local-demo-fixtures.workspace-id=${workspace_id} --nexa.local-demo-fixtures.buyer-user-id=${buyer_user_id} --nexa.local-demo-fixtures.buyer-membership-id=${buyer_membership_id} --nexa.local-demo-fixtures.buyer-client-code=${buyer_client_code}" \
	spring-boot:run
