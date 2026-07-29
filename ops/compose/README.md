# Nexa dual runtime

Este archivo es la orquestación canónica para comparar los runtimes modernos y legacy. Los perfiles son independientes y comparten ningún volumen ni red.

```bash
cp .env.example .env.local
docker compose -f compose.yml --profile modern up --build -d
docker compose -f compose.yml --profile legacy up --build -d
docker compose -f compose.yml --profile modern --profile legacy ps
docker compose -f compose.yml --profile modern --profile legacy down
```

Modern expone API `8080`, Platform `4200`, Portal `4300` y mantiene PostgreSQL privado en `nexa-modern-data`; no publica puerto de base de datos. Legacy expone API `5068`, Platform `5173`, Portal `5174` y PostgreSQL `5433`; solo Legacy usa `nexa-legacy-postgres-data`.

Modern requiere `NEXA_MODERN_POSTGRES_DB`, `NEXA_MODERN_POSTGRES_USER` y `NEXA_MODERN_POSTGRES_PASSWORD` en `.env.local`. `NEXA_DEV_BOOTSTRAP_ENABLED` queda desactivado por defecto.

Los secretos de `.env.local` son obligatorios para Legacy y no se versionan.
