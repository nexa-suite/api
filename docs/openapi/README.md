# OpenAPI local

OpenAPI y Swagger UI se habilitan únicamente con el perfil `local`.

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run
```

Con la aplicación local ejecutándose, exporta el documento generado sin agregar una copia manual al repositorio:

```bash
./docs/openapi/export-local.sh
```

El script escribe `docs/openapi/openapi.json` a partir de `/v3/api-docs`. El documento se genera desde los controladores y DTOs actuales; no es una fuente contractual separada.
