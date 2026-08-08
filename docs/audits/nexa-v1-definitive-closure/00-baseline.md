# NEXA V1 definitive closure — baseline

Fecha de captura: 2026-08-04 (America/Lima). Subtask: 0. Estado: verificado antes de cambios de código.

## Alcance y fuentes

- Implementación canónica: `api`, `platform`, `portal`.
- Referencia de solo lectura: `api-asp`, `platform-vue`, `portal-vue`, `03-design/assets/screenshots`.
- Fuera de este cierre: Website, Mobile, IoT productivo, GPS en vivo, certificación SUNAT productiva y MFA.
- Orden de autoridad: objetivo actual → Modern verificado → documentación activa → Legacy verificado → reportes históricos.
- Legacy no es un repositorio Git en esta copia; se verificará su inmutabilidad con hashes de archivos, no con `git status`.

## Git inicial

| Repo | Baseline | Rama inicial | Tracking | Estado | Rama de trabajo |
|---|---|---|---|---|---|
| API | `3f8f443428255873364de706193327a180ed4bbf` | `integration/final-patch-v1` | `origin/integration/final-patch-v1` | limpio | `feature/nexa-v1/definitive-functional-foundation` |
| Platform | `12b2af3bed7981c7b3e8d0a68fde17d1010c5a2e` | `integration/final-patch-v1` | `origin/integration/final-patch-v1` | limpio | `feature/nexa-v1/definitive-functional-foundation` |
| Portal | `5020addc6814aa37d6053bab008f1ecb3ad68ea6` | `integration/final-patch-v1` | `origin/integration/final-patch-v1` | limpio | `feature/nexa-v1/definitive-functional-foundation` |

No existía una rama local o remota con el nombre de trabajo. No se creó worktree adicional.

Hashes de solo lectura Legacy al abrir la rama: API ASP.NET `5cb372388066a0672c4f3e6008691ac7ae5ccc59a5610e18730fdfe1228ffd88`; Platform Vue `37dc1575db294fcbe2013350c5041d270371e3527cc8969f5e4aa07f3b75b3e1`; Portal Vue `cade14e86ff5e8d923dc77216abe1fda8a16adbbe978b0b9439ba02eb7cf23fa`.

## Inventario comprobado

| Elemento | Resultado |
|---|---:|
| User Stories activas en especificación | 107 |
| Technical Stories activas en especificación | 17 |
| Controllers ASP.NET Legacy | 38 |
| Controllers Spring Modern | 30 |
| Métodos HTTP declarados en controllers Spring | 251 |
| Rutas OpenAPI estáticas | 213 |
| Operaciones OpenAPI estáticas | 260 |
| Migraciones Flyway | 63 (`V1`–`V63`) |
| Fuentes Java principales | 889 |
| Fuentes Java de prueba | 137 compiladas; 92 clases `*Test(s)` |
| Clientes/servicios HTTP Platform | 35 archivos; 131 llamadas HTTP estáticas |
| Clientes/servicios HTTP Portal | 31 archivos; 56 llamadas HTTP estáticas |
| Capturas bajo `03-design/assets/screenshots` | 125 |

## Claims reportados: clasificación inicial

| Claim | Evidencia actual | Clasificación |
|---|---|---|
| API 327/327 | `./mvnw test`: 327 ejecutados, 0 fallos, **80 omitidos** | verificado solo para suite por defecto; no prueba integración obligatoria |
| Arquitectura 11/11 | reportes Surefire por separar y suite de integración pendiente | no verificado aún |
| OpenAPI 213 paths / 260 ops | `jq` sobre `docs/openapi/openapi.json` | verificado estático; paridad runtime pendiente |
| OpenAPI `0.9.0` | `pom.xml` y contrato estático | verificado declarado; runtime pendiente |
| Flyway `V1`–`V63` | inventario y migración fresca de suite por defecto | verificado inventario/fresco; upgrade obligatorio pendiente |
| Platform E2E 26 | helper acepta cualquier ruta distinta de `/sign-in`; runtime `127.0.0.1` roto | claim inválido como prueba de aceptación actual |
| Portal E2E 10 | helper acepta cualquier ruta distinta de `/sign-in`; preview `127.0.0.1` roto | claim inválido como prueba de aceptación actual |
| Docker healthy | 10 servicios activos; frontend health solo prueba Nginx estático | verificado container-health, no funcionalidad |
| GitHub CI exitoso | runs baseline API `30936715491/30936715377/30936715444`, Platform `30932728013`, Portal `30932729318` | verificado para HEAD baseline; no cubre cambios de esta rama |

## Baseline local

- API: `./mvnw test` verde, 327/327, 80 skips. Durante teardown, schedulers intentan usar Testcontainers ya detenido y generan errores de conexión no contabilizados como fallos; deuda de aislamiento de tests.
- Platform: `npm test` verde, 52 archivos / 97 tests.
- Portal: `npm test` verde, 37 archivos / 73 tests.
- Java local: 26.0.1; compilación efectiva `--release 25`.
- Spring Boot: 4.1.0. Spring Modulith: 2.1.0. Angular Platform/Portal: 22.x. TypeScript: 6.0.x.

## P0 reproducidos antes de editar

1. Portal `http://127.0.0.1:4300/sign-in`: preview `icisa` termina en “Workspace not recognized / Not recognized”. El bundle usa `http://localhost:8080`; CSP y CORS no aceptan el host real.
2. `POST http://127.0.0.1:4300/api/v1/auth/workspace-previews`, Origin `http://127.0.0.1:4300`: HTTP 403, body `Invalid CORS request`.
3. Mismo POST directo a `http://127.0.0.1:8080`: HTTP 403. Con Origin `http://localhost:4300`: HTTP 200 y `recognized=true`, `displayName=ICISA Workspace`.
4. Platform `http://127.0.0.1:4200/`: termina en `/forbidden`. El redirect dinámico de raíz se evalúa sin usuario y elige Forbidden antes de que el guard fuerce Sign In.
5. API runtime: scheduler `ChangeEventPersistenceAdapter.removeExpiredBatch` falla repetidamente con `permission denied for function purge_expired_change_events`; `V14` solo concedió ejecución a `CURRENT_USER` migrador, no a `nexa_runtime`.

## Gate Subtask 0

La línea base no se considera cerrada hasta que los mapas de paridad, rutas, consumidores, migraciones y screenshots estén auditados en los documentos hermanos. Ningún claim de negocio se eleva a “complete” por una suite agregada verde.
