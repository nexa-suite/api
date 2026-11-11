# OpenAPI contract

OpenAPI y Swagger UI se habilitan con el perfil `local` o mediante la opción explícita `NEXA_SWAGGER_ENABLED=true`. Fuera de esas opciones siguen deshabilitados.

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

La candidata v2 asigna el nombre OpenAPI `CatalogPricingPreviewRequest` al cuerpo de `/api/v1/catalog/pricing-preview`; sus campos wire siguen siendo `items` y `asOf`. `/api/v1/public/contact-requests` conserva `Request` para contacto público. `OpenApiContractIT` compara runtime y snapshot rastreado cuando `nexa.openapi.write-snapshot` está desactivado. Si está activado, primero escribe el contrato runtime y luego compara ese archivo; CI usa esta opción sólo en el paso diagnóstico que sigue a un `verify` fallido. Cuando ese paso termina correctamente, CI adjunta el candidato como artifact, pero no actualiza el snapshot rastreado ni verifica su paridad previa. Para actualizarlo, ejecuta `./docs/openapi/export-local.sh` contra la aplicación local y revisa el diff junto con el cambio de API. Los hallazgos de compatibilidad contra snapshots publicados requieren revisión explícita, migración y documentación según la política de releases; el número mayor por sí solo no los exime.

El contrato de instrucciones operativas Driver/Dispatch y sus límites de autorización está descrito en [`delivery-instructions.md`](./delivery-instructions.md).

`GET /api/v1/buyer/delivery-lead-policy` devuelve la política global fija del Buyer: cinco días hábiles de lunes a viernes desde el día siguiente, con fecha calculada en `America/Lima` y sin calendario de feriados. El contrato integrado está en [`buyer-delivery-lead-policy.md`](../integration/buyer-delivery-lead-policy.md).

## Buyer credit visibility

`GET /api/v1/client-accounts/me/credit-exposure` resolves the active account from the verified PORTAL membership and requires `payment.read`. It returns existing BC-07 credit limit, exposure, reservations, outstanding receivables, available credit and timestamp facts, plus `creditEligible`, which requires an active credit account and credit-allowing customer terms. Buyer `availableCredit` is zero when terms are absent or do not allow credit. It accepts no customer-account selector. The existing workforce `/{clientAccountId}/credit-exposure` route retains its `client.read` requirement and unmasked exposure amounts.

This additive read supports Buyer financial visibility in Web and Mobile. It neither creates a stored-value wallet nor changes credit or payment posting rules.

## Buyer Catalog favorites

The API implements the Owner-requested personal Buyer preference on a catalog
offer's `sellableSkuId`. This is an API implementation requested by the Owner
for BC-02; the current Blueprint does not define a favorites feature.
`GET /api/v1/buyer/favorites` returns a bounded page for the verified Buyer
membership, `PUT /api/v1/buyer/favorites/{sellableSkuId}` idempotently saves a
currently active Buyer-visible offer, and `DELETE` on that SKU path idempotently
removes the association. The Tenant database stores only the membership/SKU
association; response offer data is resolved from current BC-03 Catalog
visibility and pricing rules. Page items keep `sellableSkuId` and `favoritedAt`
when an offer is no longer active or visible, with `currentOffer: null`, so the
Buyer can identify and delete the stored association. `totalItems` counts all
stored preferences.
The route is enabled in the `local` profile by
`nexa.tenant-business.catalog-favorites.enabled`.

## Inventory lot temperature audit

The read-only BC-05 lot temperature route and its Warehouse/BOM authorization
scope are described in [`inventory-lot-temperature-readings.md`](./inventory-lot-temperature-readings.md).


## Swagger para presentación

Swagger abre el grupo `complete`, con todas las operaciones disponibles en el
runtime y los tags desplegados. El selector conserva `presentation` como vista
acotada opcional para autenticación, marcas, categorías y almacenes.
`/v3/api-docs` conserva el contrato completo usado por consumidores y por el
snapshot. `/v3/api-docs/presentation` excluye operaciones deprecated; los aliases
siguen disponibles en runtime y en `complete` por compatibilidad. No se elimina
un endpoint publicado por aparecer tachado: su retirada requiere análisis de
consumidores y migración.

La interfaz inicia con tags abiertos y operaciones cerradas, sin renderizar el panel global de schemas,
con filtro y sin resaltado de sintaxis. Los cuerpos y respuestas siguen
consultables al abrir cada operación. `Try it out` sigue siendo explícito; no se
almacena el token entre recargas. Estas opciones están en `swagger-ui.yml`.
Referencia: [configuración oficial de springdoc](https://springdoc.org/properties.html).

### Ensayo CRUD con datos de prueba

Usar una instancia local con Tenant provisionado, fixtures y capacidades de
catálogo/almacenes habilitadas; consultar [operaciones locales](../../ops/README.md).
La vista Swagger no habilita capacidades desactivadas del despliegue. No usar
registros reales para ensayar. Un `503` por capacidad no disponible se resuelve
preparando el entorno, no quitando controles de autorización.

1. Obtener `accessToken` fuera del navegador. En Postman o un cliente HTTP de
   terminal, ejecutar `POST /api/v1/authentication/sign-in` con
   `Content-Type: application/json` y `X-Nexa-Client: NATIVE`, sin `Origin`.
   Usar cuenta de prueba autorizada y este cuerpo, con sus datos:

   ```json
   {"identifier":"USUARIO_DE_PRUEBA","password":"CONTRASEÑA_DE_PRUEBA","workspaceSlug":"WORKSPACE_DE_PRUEBA","surface":"PLATFORM"}
   ```

2. Abrir `/swagger-ui/index.html`, grupo `presentation`. En **Authorize / bearerAuth**,
   pegar `accessToken` sin prefijo `Bearer`. No pegar refresh token. Swagger ejecuta
   las operaciones contra el mismo origen de la página, incluido HTTPS en Render.
   El navegador envía `Origin` en el login; `X-Nexa-Client: NATIVE` no elude esa
   restricción. El login browser requiere origen permitido y CSRF existentes.
3. Crear marca con `POST /api/v1/catalog/brands`, `Idempotency-Key` nuevo y cuerpo:

   ```json
   {"slug":"presentacion-marca-01","name":"Marca presentación","description":"Datos de prueba"}
   ```

4. Copiar `id` y cabecera `ETag` de creación completa, incluidas comillas.
   Leer `GET /api/v1/catalog/brands/{id}` para comprobar datos. Este GET devuelve
   `version` en el cuerpo, no cabecera `ETag`.
5. Modificar con `PATCH /api/v1/catalog/brands/{id}`, mismo cuerpo con nombre
   cambiado y `If-Match` igual al último `ETag`.
6. Desactivar con `POST /api/v1/catalog/brands/{id}/deactivations`, usando el
   nuevo `ETag`. La baja es lógica (`INACTIVE`); no existe DELETE físico de marca.
7. Repetir con categorías: `/api/v1/catalog/categories`, cuerpo con `slug`,
   `name`, `description` y `parentId: null`. Usar los ETags nuevos en cada cambio.

Sin token debe retornar `401`; usuario sin permiso debe recibir `403`; omitir
`If-Match` en actualización debe retornar `428`. No reutilizar un ETag viejo.
Un mismo `Idempotency-Key` identifica el mismo intento de creación, no otra marca.

### Comprobación técnica

```bash
./mvnw -Dnexa.integration.enabled=true -Dtest=SwaggerSecurityConfigurationTests,OpenApiContractIT,SwaggerPresentationIT,CatalogManagementIT#ownerCreatesAndReplaysCategoryThenMustUseIfMatchForUpdate+warehouseCanReadCatalogButCannotManageTaxonomy clean package
```

Requiere Docker para PostgreSQL efímero de integración. Verifica paridad del
contrato completo, selección inicial, reducción de payload, aliases fuera de la
vista de presentación, CRUD de marca y permisos/precondiciones de categorías.
Medir carga de navegador e inicio del servidor por separado: reducir contrato
no elimina una eventual espera de arranque del alojamiento.


La aprobación histórica en `migrations/v2.0.1.json` permanece inmutable. El gate
puede reutilizarla para un patch posterior del mismo major/minor únicamente si
el snapshot revisado conserva su SHA256 y el documento nuevo cambia solo
`info.version`. Los schemas, seguridad, findings y baselines deben conservarse
exactamente; cualquier cambio adicional requiere evaluación independiente.


## Autorización y validaciones durante el ensayo

Para consultas y cambios de usuario se usa **bearerAuth**, con el access token
actual. No es necesario rellenar los demás esquemas de Authorize:

- `refreshCookie` pertenece a renovación de sesión browser, con cookie HttpOnly,
  origen permitido y CSRF; no reemplaza el access token.
- `nativeRefreshToken` pertenece a renovación nativa y requiere
  `X-Nexa-Client: NATIVE`. El refresh token rota; no usarlo como bearer.
- `contextTicket` permite escoger un contexto autorizado cuando el login de
  identidad todavía no creó sesión de Tenant/Workspace. No autoriza el catálogo.
- `systemOperator` y la pareja `internalOperatorId`/`internalOperatorToken`
  pertenecen a administración interna. No usar credenciales internas para
  demostrar acciones de Buyer, Ventas o Almacén.

`PLATFORM` y `PORTAL` son superficies, no roles. El servidor revalida la sesión,
la membership, permisos y alcance de cada petición; elegir una superficie no
concede permisos adicionales.

`ETag` e `If-Match` protegen la versión del recurso: leer la versión actual y
reenviarla al cambiarlo. Un header ausente puede producir `428`; una versión
obsoleta puede producir `409` o `412` según el contrato de esa operación.
`Idempotency-Key` identifica un mismo intento de mutación para evitar que una
repetición cree un segundo efecto. No reutilizar una clave para otro payload.

Resultados negativos también deben explicarse: `401` significa credencial
inválida/ausente; `403`, acceso no autorizado; `400`, datos inválidos;
`503 TECHNICAL_CAPABILITY_UNAVAILABLE`, una capacidad que el despliegue todavía
no ofrece. Un token válido y una ruta visible en Swagger no habilitan una
capacidad cloud desactivada.

Para una demostración completa de una funcionalidad, comprobar acceso,
lectura con datos reales, cambio autorizado cuando corresponda, persistencia y
sus rechazos previstos. No declarar todas las operaciones operativas por haber
cargado el documento OpenAPI; probar cada recorrido escogido y registrar los
límites restantes.
