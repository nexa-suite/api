#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
env_file="${api_dir}/.env.local"

random_password() {
  openssl rand -base64 32 | tr -d '\n'
}

umask 077
cat > "${env_file}" <<EOF
NEXA_DATABASE_URL=jdbc:postgresql://localhost:5432/nexa
NEXA_DATABASE_USERNAME=nexa
NEXA_DATABASE_PASSWORD=$(random_password)
NEXA_SECURITY_ISSUER=http://localhost:8080
NEXA_SECURITY_AUDIENCE=nexa-local
NEXA_SECURITY_RSA_PUBLIC_KEY=./.local-keys/access-token-public.pem
NEXA_SECURITY_RSA_PRIVATE_KEY=./.local-keys/access-token-private.pem
NEXA_BCRYPT_STRENGTH=12
NEXA_DEV_BOOTSTRAP_ENABLED=true
NEXA_DEV_TENANT_NAME=Nexa Local
NEXA_DEV_TENANT_SLUG=nexa-local
NEXA_DEV_WORKSPACE_NAME=Nexa Local Workspace
NEXA_DEV_WORKSPACE_SLUG=nexa-local
NEXA_DEV_OWNER_EMAIL=owner@nexa.local
NEXA_DEV_OWNER_PASSWORD=$(random_password)
NEXA_DEV_SALES_EMAIL=sales@nexa.local
NEXA_DEV_SALES_PASSWORD=$(random_password)
NEXA_DEV_WAREHOUSE_EMAIL=warehouse@nexa.local
NEXA_DEV_WAREHOUSE_PASSWORD=$(random_password)
NEXA_DEV_LOGISTICS_EMAIL=logistics@nexa.local
NEXA_DEV_LOGISTICS_PASSWORD=$(random_password)
NEXA_DEV_BUYER_EMAIL=buyer@nexa.local
NEXA_DEV_BUYER_PASSWORD=$(random_password)
EOF

printf '%s\n' '.env.local'
