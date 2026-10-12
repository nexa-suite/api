#!/bin/sh
set -eu
umask 077

source_root=/source
projection_root=/projection
api_uid=10001
api_gid=10001
credential_files='credentials.properties state.properties policy-snapshot-writer.properties wallet-recharge-worker.properties business-documents-worker.properties payment-callback-worker.properties business-traceability-worker.properties'

fail() {
	printf '%s\n' "$1" >&2
	exit 1
}

private_mode() {
	path="$1"
	expected="$2"
	actual="$(stat -c '%a' "$path" 2>/dev/null)" || return 1
	[ "$actual" = "$expected" ]
}

private_owner() {
	path="$1"
	actual="$(stat -c '%u:%g' "$path" 2>/dev/null)" || return 1
	[ "$actual" = "$api_uid:$api_gid" ]
}

require_tenant_id() {
	printf '%s\n' "$1" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
}

copy_one_file() {
	source_file="$1"
	target_dir="$2"
	credential_file="$3"
	target_file="$target_dir/$credential_file"
	if [ -L "$source_file" ]; then
		fail 'Local Tenant credential entries cannot be symbolic links.'
	fi
	if [ ! -e "$source_file" ]; then
		if [ -L "$target_file" ]; then
			fail 'API credential projection cannot contain symbolic links.'
		fi
		if [ -e "$target_file" ]; then
			[ -f "$target_file" ] || fail 'API credential projection entries must be regular files.'
			private_mode "$target_file" 600 && private_owner "$target_file" \
				|| fail 'API credential projection files must be owner-only for API UID 10001.'
			rm -- "$target_file"
		fi
		return
	fi
	[ -f "$source_file" ] || fail 'Local Tenant credential entries must be regular files.'
	private_mode "$source_file" 600 \
		|| fail 'Local Tenant credential files must be owner-only (0600).'
	if [ -f "$target_file" ] && [ ! -L "$target_file" ] \
		&& private_mode "$target_file" 600 && private_owner "$target_file" \
		&& cmp -s "$source_file" "$target_file"; then
		return
	fi
	if [ -e "$target_file" ] || [ -L "$target_file" ]; then
		[ ! -L "$target_file" ] && [ -f "$target_file" ] \
			|| fail 'API credential projection entries must be regular files.'
	fi
	temporary_file="$target_dir/.$credential_file.projecting"
	cp -- "$source_file" "$temporary_file"
	chown "$api_uid:$api_gid" "$temporary_file"
	chmod 600 "$temporary_file"
	mv -f -- "$temporary_file" "$target_file"
}

sync_tenant() {
	source_tenant="$1"
	tenant_id="${source_tenant##*/}"
	target_tenant="$projection_root/$tenant_id"
	require_tenant_id "$tenant_id" || fail 'Local Tenant credential directory name is not a Tenant UUID.'
	[ ! -L "$source_tenant" ] && [ -d "$source_tenant" ] \
		|| fail 'Local Tenant credential entries must be real directories.'
	private_mode "$source_tenant" 700 \
		|| fail 'Local Tenant credential directories must be owner-only (0700).'

	if [ -L "$target_tenant" ]; then
		fail 'API credential projection cannot contain symbolic links.'
	fi
	if [ -e "$target_tenant" ]; then
		[ -d "$target_tenant" ] || fail 'API Tenant credential projection must be a directory.'
		private_mode "$target_tenant" 700 && private_owner "$target_tenant" \
			|| fail 'API Tenant credential directories must be owner-only for API UID 10001.'
		for credential_file in $credential_files; do
			copy_one_file "$source_tenant/$credential_file" "$target_tenant" "$credential_file"
		done
		return
	fi

	staging_tenant="$projection_root/.stage-$tenant_id-$$"
	mkdir -m 700 -- "$staging_tenant"
	for credential_file in $credential_files; do
		copy_one_file "$source_tenant/$credential_file" "$staging_tenant" "$credential_file"
	done
	if [ -z "$(find "$staging_tenant" -mindepth 1 -maxdepth 1 -type f -print -quit)" ]; then
		rmdir -- "$staging_tenant"
		return
	fi
	chown "$api_uid:$api_gid" "$staging_tenant"
	chmod 700 "$staging_tenant"
	mv -- "$staging_tenant" "$target_tenant"
}

sync_all_tenants() {
	for source_tenant in "$source_root"/*; do
		[ -e "$source_tenant" ] || continue
		[ -d "$source_tenant" ] || continue
		sync_tenant "$source_tenant"
	done
	for target_tenant in "$projection_root"/*; do
		[ -e "$target_tenant" ] || continue
		[ -d "$target_tenant" ] || continue
		tenant_id="${target_tenant##*/}"
		case "$tenant_id" in .stage-*) continue ;; esac
		require_tenant_id "$tenant_id" || continue
		if [ ! -d "$source_root/$tenant_id" ]; then
			[ ! -L "$target_tenant" ] && private_mode "$target_tenant" 700 \
				&& private_owner "$target_tenant" \
				|| fail 'Stale API Tenant credential projection is unsafe.'
			rm -rf -- "$target_tenant"
		fi
	done
}

[ ! -L "$source_root" ] && [ -d "$source_root" ] \
	|| fail 'Local Tenant credential source must be a real directory.'
private_mode "$source_root" 700 \
	|| fail 'Local Tenant credential source must be owner-only (0700).'
[ ! -L "$projection_root" ] && [ -d "$projection_root" ] \
	|| fail 'API credential projection must be a real volume directory.'

chmod 700 "$projection_root"
chown "$api_uid:$api_gid" "$projection_root"
for stale_stage in "$projection_root"/.stage-*; do
	[ -e "$stale_stage" ] || continue
	[ ! -L "$stale_stage" ] && [ -d "$stale_stage" ] \
		|| fail 'Stale API credential staging entry is unsafe.'
	rm -rf -- "$stale_stage"
done
rm -f -- /run/tenant-credentials-sync/ready

sync_all_tenants
touch /run/tenant-credentials-sync/ready
chmod 600 /run/tenant-credentials-sync/ready
while :; do
	sleep 2
	sync_all_tenants
done
