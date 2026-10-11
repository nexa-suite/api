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
include `minio` or `s3` start and wait for the MinIO service before the API;
`local` uses the private filesystem adapter and does not start MinIO. The
security/load workflow uses that local adapter because its runner cannot
authorize a pull of the pinned MinIO image. This keeps document workflows
enabled in that runtime; it does not verify the S3-compatible adapter.

Legacy expone API ASP.NET `5068`, Platform Vue `5173` y Portal Vue `5174`. PostgreSQL no publica puerto y vive en `nexa-legacy-data`.

Las redes, volúmenes, credenciales y migraciones son independientes. Los overrides `modern.db-admin.compose.yml` y `legacy.db-admin.compose.yml` son opcionales y no se habilitan por defecto.
