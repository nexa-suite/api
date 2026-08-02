#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
env_file="${api_dir}/.env.local"

random_password() {
  openssl rand -base64 32 | tr -d '\n'
}

umask 077
modern_postgres_password=$(random_password)
cat > "${env_file}" <<EOF
NEXA_POSTGRES_PASSWORD=$(random_password)
NEXA_SECURITY_JWT_SIGNING_KEY=$(random_password)
NEXA_DATABASE_URL=jdbc:postgresql://localhost:5432/nexa
NEXA_DATABASE_USERNAME=nexa
NEXA_DATABASE_PASSWORD=${modern_postgres_password}
NEXA_MODERN_POSTGRES_DB=nexa
NEXA_MODERN_POSTGRES_USER=nexa
NEXA_MODERN_POSTGRES_PASSWORD=${modern_postgres_password}
NEXA_MODERN_SPRING_PROFILE=local
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
NEXA_DEV_TENANT_NAME="ICISA"
NEXA_DEV_TENANT_SLUG=icisa
NEXA_DEV_WORKSPACE_NAME="ICISA Workspace"
NEXA_DEV_WORKSPACE_SLUG=icisa
NEXA_DEV_OWNER_EMAIL=carlos.rios@icisa.pe
NEXA_DEV_OWNER_PASSWORD=$(random_password)
NEXA_DEV_SALES_EMAIL=valeria.sanchez@icisa.pe
NEXA_DEV_SALES_PASSWORD=$(random_password)
NEXA_DEV_WAREHOUSE_EMAIL=roberto.garcia@icisa.pe
NEXA_DEV_WAREHOUSE_PASSWORD=$(random_password)
NEXA_DEV_LOGISTICS_EMAIL=logistics@icisa.pe
NEXA_DEV_LOGISTICS_PASSWORD=$(random_password)
NEXA_DEV_BUYER_EMAIL=elena.litano@icisa.pe
NEXA_DEV_BUYER_PASSWORD=$(random_password)
EOF

printf '%s\n' '.env.local'
