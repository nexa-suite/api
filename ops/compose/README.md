# Nexa dual runtime

Este archivo es la orquestación canónica para comparar los runtimes modernos y legacy. Los perfiles son independientes y comparten ningún volumen ni red.

```bash
cp .env.example .env.local
docker compose -f compose.yml --profile modern up --build -d
docker compose -f compose.yml --profile legacy up --build -d
docker compose -f compose.yml --profile modern --profile legacy ps
docker compose -f compose.yml --profile modern --profile legacy down
```

Modern expone API `8080`, Platform `4200` y Portal `4300`, sin PostgreSQL. Legacy expone API `5068`, Platform `5173`, Portal `5174` y PostgreSQL `5433`; solo Legacy usa `nexa-legacy-postgres-data`.

Los secretos de `.env.local` son obligatorios para Legacy y no se versionan.
