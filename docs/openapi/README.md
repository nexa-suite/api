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

El snapshot heredado contiene una colisión previa en `CatalogPricingPreviewController.Request`: `/api/v1/catalog/pricing-preview` referencia el schema genérico `Request`, cuyos campos publicados corresponden a contacto público (`requestType`, `name`, `email`, `companyName`, `message`) y no al DTO de precios (`items`, `asOf`). Este desacuerdo ya existe en la rama base. Se conserva el contrato publicado y se documenta como deuda del snapshot; corregirlo requiere una decisión explícita de compatibilidad.

El contrato de instrucciones operativas Driver/Dispatch y sus límites de autorización está descrito en [`delivery-instructions.md`](./delivery-instructions.md).

## Buyer credit visibility

`GET /api/v1/client-accounts/me/credit-exposure` resolves the active account from the verified PORTAL membership and requires `payment.read`. It returns existing BC-07 credit limit, exposure, reservations, outstanding receivables, available credit and timestamp facts. It accepts no customer-account selector. The existing workforce `/{clientAccountId}/credit-exposure` route retains its `client.read` requirement.

This additive read supports Buyer financial visibility in Web and Mobile. It neither creates a stored-value wallet nor changes credit or payment posting rules.
