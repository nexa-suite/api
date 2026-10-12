# Nexa local runtimes

Modern is the current API runtime. Legacy remains available for comparison and
compatibility. Both use the canonical `api/.env.local` file.

Run the modern stack for current API work. `compare-up.sh` intentionally starts
both Modern and Legacy for side-by-side comparison.

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

Modern expone API `8080`, Platform `4200` y Portal `4300`. PostgreSQL no publica puerto y vive en `nexa-modern-data`.

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

Legacy expone API ASP.NET `5068`, Platform Vue `5173` y Portal Vue `5174`. PostgreSQL no publica puerto y vive en `nexa-legacy-data`.

Las redes, volúmenes, credenciales y migraciones son independientes. Los overrides `modern.db-admin.compose.yml` y `legacy.db-admin.compose.yml` son opcionales y no se habilitan por defecto.
