#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
env_file="${api_dir}/.env.local"

random_password() {
  openssl rand -base64 32 | tr -d '\n'
}

existing_value() {
  key="$1"
  if [ -f "${env_file}" ]; then
    sed -n "s/^${key}=//p" "${env_file}" | head -n 1
  fi
}

umask 077
modern_postgres_password=$(existing_value NEXA_MODERN_POSTGRES_PASSWORD)
if [ -z "${modern_postgres_password}" ]; then
  modern_postgres_password=$(random_password)
fi
demo_password="${NEXA_DEV_DEMO_PASSWORD:-NexaLocal!2026#}"
minio_password=$(existing_value NEXA_MINIO_ROOT_PASSWORD)
if [ -z "${minio_password}" ]; then
  minio_password=$(random_password)
fi
cat > "${env_file}" <<EOF
NEXA_POSTGRES_PASSWORD=${modern_postgres_password}
NEXA_SECURITY_JWT_SIGNING_KEY=$(random_password)
NEXA_DATABASE_URL=jdbc:postgresql://localhost:5432/nexa
NEXA_DATABASE_USERNAME=nexa
NEXA_DATABASE_PASSWORD=${modern_postgres_password}
NEXA_MODERN_POSTGRES_DB=nexa
NEXA_MODERN_POSTGRES_USER=nexa
NEXA_MODERN_POSTGRES_PASSWORD=${modern_postgres_password}
NEXA_MINIO_ROOT_USER=nexa-minio
NEXA_MINIO_ROOT_PASSWORD=${minio_password}
NEXA_MODERN_SPRING_PROFILE=local,minio
NEXA_SECURITY_ISSUER=http://localhost:8080
NEXA_SECURITY_AUDIENCE=nexa-local
NEXA_PASSWORD_RESET_THROTTLE_KEY=$(random_password)
NEXA_NOTIFICATION_OUTBOX_KEY=$(random_password)
NEXA_SYSTEM_OPERATOR_TOKEN=$(random_password)
NEXA_SECURITY_RSA_PUBLIC_KEY=./.local-keys/access-token-public.pem
NEXA_SECURITY_RSA_PRIVATE_KEY=./.local-keys/access-token-private.pem
NEXA_BCRYPT_STRENGTH=12
NEXA_REFRESH_TOKEN_TTL=P30D
NEXA_DEV_BOOTSTRAP_ENABLED=true
NEXA_DEV_DEMO_PASSWORD=${demo_password}
NEXA_DEV_TENANT_NAME="ICISA"
NEXA_DEV_TENANT_SLUG=icisa
NEXA_DEV_WORKSPACE_NAME="ICISA Workspace"
NEXA_DEV_WORKSPACE_SLUG=icisa
NEXA_DEV_OWNER_EMAIL=owner@icisa.test
NEXA_DEV_OWNER_PASSWORD=${demo_password}
NEXA_DEV_TENANT_ADMIN_EMAIL=tenant.admin@icisa.test
NEXA_DEV_TENANT_ADMIN_PASSWORD=${demo_password}
NEXA_DEV_SALES_EMAIL=sales@icisa.test
NEXA_DEV_SALES_PASSWORD=${demo_password}
NEXA_DEV_WAREHOUSE_EMAIL=warehouse@icisa.test
NEXA_DEV_WAREHOUSE_PASSWORD=${demo_password}
NEXA_DEV_LOGISTICS_EMAIL=logistics@icisa.test
NEXA_DEV_LOGISTICS_PASSWORD=${demo_password}
NEXA_DEV_BUYER_EMAIL=buyer@icisa.test
NEXA_DEV_BUYER_PASSWORD=${demo_password}
EOF

printf '%s\n' '.env.local'
