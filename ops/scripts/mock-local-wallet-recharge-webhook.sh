#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

API_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="$API_DIRECTORY/.env.local"
COMPOSE_FILE="$API_DIRECTORY/ops/compose/modern.compose.yml"
WEBHOOK_URL='http://127.0.0.1:8080/api/v1/payments/integrations/stripe/webhooks'
UUID_PATTERN='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

fail() { printf '%s\n' "$1" >&2; exit 1; }
if [[ $# != 1 || ! "$1" =~ $UUID_PATTERN ]]; then
	fail 'Usage: ops/scripts/mock-local-wallet-recharge-webhook.sh <recharge-uuid>'
fi
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || fail 'Local API environment file is missing or unsafe.'

# Match modern-up.sh's local environment loading without echoing any values.
set -a
. "$ENV_FILE"
set +a
[[ "${NEXA_PAYMENTS_PROVIDER:-deterministic}" == deterministic ]] \
	|| fail 'The local signed PSP helper requires NEXA_PAYMENTS_PROVIDER=deterministic.'
case ",${NEXA_MODERN_SPRING_PROFILE:-local,minio}," in
	*,local,*) ;;
	*) fail 'The local signed PSP helper requires the API local profile.' ;;
esac
[[ -n "${STRIPE_WEBHOOK_SECRET:-}" ]] || fail 'STRIPE_WEBHOOK_SECRET is empty.'

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T modern-api sh -ec '
  [ "${NEXA_PAYMENTS_PROVIDER:-}" = deterministic ]
  case ",${SPRING_PROFILES_ACTIVE:-}," in *,local,*) ;; *) exit 1 ;; esac
' >/dev/null 2>&1 || fail 'The running API is not configured with the local deterministic PSP.'

recharge_id="$1"
route="$(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" exec -T modern-postgres \
	psql -X -qAt -F '|' -v ON_ERROR_STOP=1 -U "$NEXA_MODERN_POSTGRES_USER" \
	-d "$NEXA_MODERN_POSTGRES_DB" -v "recharge_id=$recharge_id" <<'SQL'
SET app.cross_scope_workspace_scan = 'true';
SELECT r.recharge_id::TEXT, r.tenant_id::TEXT, r.workspace_id::TEXT,
       r.provider_payment_intent_id, r.amount_minor::TEXT, r.currency,
       extract(epoch FROM r.created_at)::BIGINT::TEXT
  FROM tenant_management.wallet_recharge_provider_route r
  JOIN tenant_management.tenant_business_database_binding b
    ON b.tenant_id=r.tenant_id AND b.database_identity=r.database_identity
 WHERE r.recharge_id=:'recharge_id'::UUID
   AND r.provider_code='STRIPE'
   AND r.status='AWAITING_PAYMENT'
   AND r.currency='PEN'
   AND r.provider_payment_intent_id LIKE 'pi_local_%'
   AND b.lifecycle_state='READY'
   AND b.verified_schema_manifest_sha256 IS NOT NULL
   AND b.wallet_recharge_callback_credential_secret_reference IS NOT NULL;
SQL
)" || fail 'The central pending local recharge route could not be read.'

[[ -n "$route" && "$route" != *$'\n'* ]] || fail 'No unique AWAITING_PAYMENT route exists for this recharge.'
IFS='|' read -r route_recharge_id tenant_id workspace_id payment_intent_id amount_minor currency created_epoch <<<"$route"
[[ "$route_recharge_id" == "$recharge_id" && "$tenant_id" =~ $UUID_PATTERN \
	&& "$workspace_id" =~ $UUID_PATTERN && "$payment_intent_id" =~ ^pi_local_[0-9a-f]{32}$ \
	&& "$amount_minor" =~ ^[1-9][0-9]{0,7}$ && "$currency" == PEN \
	&& "$created_epoch" =~ ^[0-9]+$ ]] || fail 'The persisted local recharge route has invalid or unsupported PSP data.'

export NEXA_LOCAL_RECHARGE_ID="$route_recharge_id"
export NEXA_LOCAL_TENANT_ID="$tenant_id"
export NEXA_LOCAL_WORKSPACE_ID="$workspace_id"
export NEXA_LOCAL_PAYMENT_INTENT_ID="$payment_intent_id"
export NEXA_LOCAL_AMOUNT_MINOR="$amount_minor"
export NEXA_LOCAL_CURRENCY="$currency"
export NEXA_LOCAL_CREATED_EPOCH="$created_epoch"
export NEXA_LOCAL_WEBHOOK_URL="$WEBHOOK_URL"
python3 - <<'PY'
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.request

secret = os.environ.get("STRIPE_WEBHOOK_SECRET", "")
if not secret:
    raise SystemExit("STRIPE_WEBHOOK_SECRET is empty.")
recharge_id = os.environ["NEXA_LOCAL_RECHARGE_ID"]
event_id = "evt_nexa_local_wallet_" + recharge_id.replace("-", "")
event = {
    "id": event_id,
    "object": "event",
    "api_version": "2024-06-20",
    "created": int(os.environ["NEXA_LOCAL_CREATED_EPOCH"]),
    "data": {"object": {"id": os.environ["NEXA_LOCAL_PAYMENT_INTENT_ID"]}},
    "livemode": False,
    "pending_webhooks": 1,
    "type": "payment_intent.succeeded",
    "payment_intent_id": os.environ["NEXA_LOCAL_PAYMENT_INTENT_ID"],
    "status": "succeeded",
    "amount": int(os.environ["NEXA_LOCAL_AMOUNT_MINOR"]),
    "currency": os.environ["NEXA_LOCAL_CURRENCY"],
    "nexa_tenant_id": os.environ["NEXA_LOCAL_TENANT_ID"],
    "nexa_workspace_id": os.environ["NEXA_LOCAL_WORKSPACE_ID"],
    "nexa_wallet_recharge_id": recharge_id,
}
payload = json.dumps(event, separators=(",", ":"), sort_keys=True).encode("utf-8")
timestamp = str(int(time.time()))
digest = hmac.new(secret.encode("utf-8"), timestamp.encode("ascii") + b"." + payload,
                  hashlib.sha256).hexdigest()
request = urllib.request.Request(
    os.environ["NEXA_LOCAL_WEBHOOK_URL"],
    data=payload,
    headers={"Content-Type": "application/json", "Stripe-Signature": f"t={timestamp},v1={digest}"},
    method="POST",
)
try:
    with urllib.request.urlopen(request, timeout=15) as response:
        if response.status != 202:
            raise SystemExit(f"Local signed PSP callback returned HTTP {response.status}.")
        receipt = json.loads(response.read())
except urllib.error.HTTPError as error:
    raise SystemExit(f"Local signed PSP callback returned HTTP {error.code}.") from None
except urllib.error.URLError:
    raise SystemExit("The local API webhook endpoint is unavailable.") from None
print(f"{receipt.get('status', 'ACCEPTED')} {event_id}")
PY
