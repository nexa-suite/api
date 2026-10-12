#!/usr/bin/env sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
api_dir=$(CDPATH= cd -- "${script_dir}/.." && pwd)
keys_dir="${api_dir}/.local-keys"
private_key="${keys_dir}/access-token-private.pem"
public_key="${keys_dir}/access-token-public.pem"
temporary_private_key=
temporary_public_key=
installed_private=0
installed_public=0

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

umask 077
if [ -L "$keys_dir" ]; then
  fail 'Local signing key directory cannot be a symbolic link.'
fi
if [ ! -e "$keys_dir" ]; then
  mkdir -m 700 "$keys_dir"
elif [ ! -d "$keys_dir" ]; then
  fail 'Local signing key path must be a directory.'
fi

for key_file in "$private_key" "$public_key"; do
  if [ -L "$key_file" ]; then
    fail 'Local signing key files cannot be symbolic links.'
  fi
  if [ -e "$key_file" ] && [ ! -f "$key_file" ]; then
    fail 'Local signing key entries must be regular files.'
  fi
done

if [ -f "$private_key" ] && [ -f "$public_key" ]; then
  printf '%s\n' '.local-keys/access-token-private.pem' '.local-keys/access-token-public.pem'
  exit 0
fi
if [ -f "$public_key" ] && [ ! -f "$private_key" ]; then
  fail 'Local public signing key exists without its private key; refusing to replace it.'
fi

cleanup() {
  if [ "$installed_public" -eq 1 ] && [ -n "$temporary_public_key" ] \
    && [ -e "$temporary_public_key" ] && [ "$temporary_public_key" -ef "$public_key" ]; then
    rm -f "$public_key"
  fi
  if [ "$installed_private" -eq 1 ] && [ -n "$temporary_private_key" ] \
    && [ -e "$temporary_private_key" ] && [ "$temporary_private_key" -ef "$private_key" ]; then
    rm -f "$private_key"
  fi
  if [ -n "$temporary_private_key" ]; then rm -f "$temporary_private_key"; fi
  if [ -n "$temporary_public_key" ]; then rm -f "$temporary_public_key"; fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

temporary_public_key="${keys_dir}/.access-token-public.$$.tmp"
if [ -f "$private_key" ]; then
  [ ! -e "$temporary_public_key" ] && [ ! -L "$temporary_public_key" ] \
    || fail 'Temporary local signing key path already exists.'
  openssl pkey -in "$private_key" -pubout -out "$temporary_public_key"
  chmod 600 "$temporary_public_key"
else
  temporary_private_key="${keys_dir}/.access-token-private.$$.tmp"
  [ ! -e "$temporary_private_key" ] && [ ! -L "$temporary_private_key" ] \
    || fail 'Temporary local signing key path already exists.'
  [ ! -e "$temporary_public_key" ] && [ ! -L "$temporary_public_key" ] \
    || fail 'Temporary local signing key path already exists.'
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$temporary_private_key"
  openssl pkey -in "$temporary_private_key" -pubout -out "$temporary_public_key"
  chmod 600 "$temporary_private_key" "$temporary_public_key"
  [ ! -e "$private_key" ] && [ ! -L "$private_key" ] \
    || fail 'Local private signing key appeared during generation; refusing to replace it.'
  ln "$temporary_private_key" "$private_key" \
    || fail 'Local private signing key appeared during generation; refusing to replace it.'
  installed_private=1
fi

[ ! -e "$public_key" ] && [ ! -L "$public_key" ] \
  || fail 'Local public signing key appeared during generation; refusing to replace it.'
ln "$temporary_public_key" "$public_key" \
  || fail 'Local public signing key appeared during generation; refusing to replace it.'
installed_public=1
installed_private=0
if [ -n "$temporary_private_key" ]; then rm -f "$temporary_private_key"; fi
rm -f "$temporary_public_key"
installed_public=0
temporary_private_key=
temporary_public_key=

printf '%s\n' '.local-keys/access-token-private.pem' '.local-keys/access-token-public.pem'
