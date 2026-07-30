#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
keys_dir="${api_dir}/.local-keys"

mkdir -p "${keys_dir}"
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "${keys_dir}/access-token-private.pem"
openssl pkey -in "${keys_dir}/access-token-private.pem" -pubout -out "${keys_dir}/access-token-public.pem"
chmod 600 "${keys_dir}/access-token-private.pem" "${keys_dir}/access-token-public.pem"
printf '%s\n' '.local-keys/access-token-private.pem' '.local-keys/access-token-public.pem'
