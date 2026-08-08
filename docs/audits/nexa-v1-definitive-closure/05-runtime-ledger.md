# Runtime ledger

## Proyecto Docker baseline

Project: `nexa-modern`. Stack dejado corriendo.

| Service | Image | Puerto host | Health | Perfil/adaptador observado |
|---|---|---|---|---|
| PostgreSQL | `postgres:18.4-alpine` | interno | healthy | DB runtime `nexa_runtime`, Flyway migrator separado |
| API | `nexa/modern-api:local` | `127.0.0.1:8080` | healthy | profiles `local,minio,observability`; payment `stripe` contra WireMock local |
| Platform | `nexa/modern-platform:local` | `127.0.0.1:4200` | healthy | Nginx estático + proxy `/api` no usado por bundle |
| Portal | `nexa/modern-portal:local` | `127.0.0.1:4300` | healthy | Nginx estático + proxy `/api` no usado por bundle |
| Mailpit | `axllent/mailpit:v1.26.0` | `127.0.0.1:8025` | healthy | SMTP local requerido |
| MinIO | `minio/minio:RELEASE.2024-10-13T13-34-11Z` | `9000/9001` | healthy | profile MinIO activo; volumen persistente |
| ClamAV | `clamav/clamav-debian:1.4.3` | `127.0.0.1:3310` | healthy | adapter TCP real ejercitado con contenido limpio y EICAR |
| Stripe mock | `wiremock/wiremock:3.13.1` | `127.0.0.1:12111` | healthy | endpoint compatible; adapter oficial Stripe Java activo; IDs/client secrets con forma Stripe válida |
| Jaeger | `jaegertracing/all-in-one:1.76.0` | `16686/4318` | running | profile observability |
| OTEL collector | `otel/opentelemetry-collector-contrib:0.132.0` | interno | running | export configurado; metrics API baseline false |

No se registran valores secretos. Solo nombres de variables, adapters y selección de profile.

## Evidencia final 2026-08-08

- `modern-up.sh` reconstruyó y recreó API, Platform y Portal con `NEXA_MODERN_SPRING_PROFILE=local,minio,observability`.
- Readiness/health: API `/actuator/health/readiness`, Platform `/health` y Portal `/health` respondieron `UP/ok`; ambos `/api-health` respondieron `UP`.
- Flyway runtime: versión `68`.
- CORS: `localhost:4200`, `localhost:4300`, `127.0.0.1:4200` y `127.0.0.1:4300` → 200; `https://evil.example` → 403.
- PostgreSQL: `nexa_runtime` puede iniciar sesión, no es superusuario, no puede omitir RLS, crear bases ni crear roles.
- Runtime catalog: 66 variants, 132 sellable SKUs con `variant_id`, 0 permisos operativos asignados a Company Owner.
- Forced RLS observada en `sales.client_account`, `sales.client_account_address`, `business_documents.*` y `payments.*` protegidos.
- OpenAPI exportado desde runtime: 223 paths; comparación con el baseline de 213 añadió 10 rutas y no removió ninguna.
- Stripe runtime: `POST /receivables/{id}/payment-intents` pasó por el SDK oficial contra WireMock; webhook `payment_intent.succeeded` firmado fue aceptado y el worker produjo settlement/receipt.
- Cross-tenant documents: lectura y descarga de un documento de otro tenant por usuario autenticado respondieron `404 Resource not found`; no se expuso metadata ni contenido.
- Documentos XML: los dos objetos XML generados en MinIO se descargaron con `mc cat` y pasaron `xmllint --noout -` (raíces UBL `Invoice`).
- Observabilidad: con perfil `observability`, Jaeger reportó servicio `nexa-api` y 20 trazas consultables en `/api/traces` después de una llamada autenticada.

## Reproducción Portal

- Browser URL: `http://127.0.0.1:4300/sign-in`.
- Runtime API base: `http://localhost:8080`.
- Browser UI: workspace `icisa`; resultado falso `recognized=false` por `catchError` genérico.
- Proxy request reproducida: `POST /api/v1/auth/workspace-previews`, Origin `http://127.0.0.1:4300`, HTTP 403, body `Invalid CORS request`.
- Headers relevantes: `Vary: Origin`, no `Access-Control-Allow-Origin`; correlation/trace IDs presentes.
- Control: Origin `http://localhost:4300` → HTTP 200, JSON reconocido.
- Console del in-app browser: sin entrada; la app consume el error y muestra estado de negocio incorrecto.
- Cookie: preview no requiere cookie. El mismatch de host también invalida el ciclo refresh cookie en Sign In.
- Evidencia: `output/playwright/nexa-v1-definitive-closure/audit-current/01-portal-workspace-not-recognized.png`.

## Reproducción Platform

- Browser URL inicial: `http://127.0.0.1:4200/`.
- Ruta final: `/forbidden`.
- Runtime API base: `http://localhost:8080`.
- Guard root: `roleLandingRedirect` retorna `/forbidden` cuando `currentUser=null`; se evalúa como redirect de child vacío.
- Restore: refresh directo al host `localhost`, incompatible con el browser host `127.0.0.1` y allowed origins baseline.
- Roles/permissions/membership/auth-version: no pudieron establecerse en esta reproducción; el fallo precede una sesión válida.
- Evidencia: `output/playwright/nexa-v1-definitive-closure/audit-current/02-platform-forbidden.png`.

## UX audit inicial

| Paso | Health | Hallazgo |
|---|---|---|
| Portal workspace preview | blocked | error de infraestructura presentado como workspace inexistente; copy duplicado |
| Portal continuation | blocked | usuario válido no puede habilitar acceso desde host documentado |
| Platform root/session restore | blocked | usuario/anónimo cae en authorization failure sin Sign In |
| Platform Forbidden recovery | blocked | “Back to work area” vuelve a root y repite ciclo |

Límite de evidencia: screenshots no prueban red, cookies, teclado ni WCAG; esos gates se cubren con HTTP, tests y browser instrumentado.
