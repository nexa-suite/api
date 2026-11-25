# Nexa local runtimes

Use Modern Compose for local API work. Both stacks read `api/.env.local`.
Run `./scripts/setup-local-environment.sh` if you still need to set it up.

```bash
./ops/compose/scripts/modern-up.sh
./ops/compose/scripts/status.sh
./ops/compose/scripts/modern-down.sh
```

To run both stacks for comparison:

```bash
./ops/compose/scripts/compare-up.sh
./ops/compose/scripts/status.sh
./ops/compose/scripts/compare-down.sh
```

The Modern stack exposes API on `8080`, Platform on `4200`, and Portal on
`4300`. PostgreSQL stays on the Compose network and uses `nexa-modern-data`.

The Modern startup script follows `NEXA_MODERN_SPRING_PROFILE`: profiles that
include `minio` or `s3` build and start the pinned MinIO Community Edition image,
then wait for its health check before starting the API. `local` alone uses the
private filesystem adapter. The security/load workflow uses `local,minio` and
runs the API against MinIO; its independent `minio-storage` job builds the same
image and runs the S3 adapter integration test without Tenant provisioning.
The source build is pinned to MinIO `RELEASE.2025-10-15T17-29-55Z` and `mc`
`RELEASE.2025-08-13T08-35-41Z`; see `minio-community/Dockerfile`. The image
runs MinIO as unprivileged UID 10001. MinIO Community Edition is archived
upstream, so this Compose service supports development and verification only.
Select a maintained object-storage provider before the Production Gate.

The Legacy stack exposes the ASP.NET API on `5068`, Vue Platform on `5173`,
and Vue Portal on `5174`. Its PostgreSQL service stays on the Compose network
and uses `nexa-legacy-data`.

Each stack has separate networks, volumes, credentials, and migrations. Use
the optional `modern.db-admin.compose.yml` or `legacy.db-admin.compose.yml`
override only when you need database administration tools.
