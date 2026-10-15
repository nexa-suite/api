#!/bin/sh
set -eu
umask 077

storage_root=/objects
fail() { printf '%s\n' "$1" >&2; exit 1; }

[ -d "$storage_root" ] && [ ! -L "$storage_root" ] \
    || fail 'Private object storage volume is missing or unsafe.'
if find "$storage_root" -xdev ! -type d ! -type f -print -quit | grep -q .; then
    fail 'Private object storage contains unsupported filesystem entries.'
fi

# Only the dedicated private volume is writable in this initializer.
find "$storage_root" -xdev -type d -exec chown 10001:10001 '{}' +
find "$storage_root" -xdev -type f -exec chown 10001:10001 '{}' +
find "$storage_root" -xdev -type d -exec chmod 0700 '{}' +
find "$storage_root" -xdev -type f -exec chmod 0600 '{}' +

[ "$(stat -c '%u:%g:%a' "$storage_root")" = '10001:10001:700' ] \
    || fail 'Private object storage ownership could not be verified.'
printf '%s\n' 'Local private object storage ownership verified.'
