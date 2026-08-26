# OpenAPI contract

OpenAPI y Swagger UI se habilitan únicamente con el perfil `local`.

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

El runtime genera contrato desde controladores, DTOs y configuración. `docs/openapi/openapi.json` es snapshot canónico, no fuente separada.

Con aplicación local ejecutándose, exporta snapshot reproducible:

```bash
./docs/openapi/export-local.sh
```

El test `OpenApiContractIT` compara `/v3/api-docs` con snapshot. Si runtime cambia sin actualizar snapshot, `verify` de integración falla.

`info.version` deriva de versión Maven durante tests y de `BuildProperties` en runtime empaquetado. No mantener versión manual adicional.

La compatibilidad de operaciones, parámetros, respuestas y schemas se valida en CI con `.github/scripts/check-openapi-compatibility.py` contra snapshot de rama base.
