# Security ledger

Target: ASVS 5 Level 2 según matriz activa. Baseline, no certificación.

| Control | Evidencia source/runtime | Estado baseline |
|---|---|---|
| Access JWT corto + refresh opaco | IAM domain/application + cookie controller | source exists; Docker host roto |
| Refresh rotation/reuse rejection | session service/tests | integration/browser pendiente |
| Cookie HttpOnly/SameSite/path | AuthenticationController | header matrix pendiente |
| CORS narrow | allowlist exacta `localhost` | secure intent, incompatible con `127.0.0.1` |
| Origin guard | `CookieOriginGuardFilter` | activo; no se debilitará |
| CSRF posture | Bearer + guarded cookie mutations | matriz pendiente |
| BOLA/BFLA | access context, permissions, scoped queries | integration/RLS pendiente |
| Authorization version | membership state + JWT/session checks | integration/browser pendiente |
| RLS | forced policies en principales tablas | cross-tenant proof pendiente |
| CSP | frontend + API headers | duplicate CSP through proxy; normalización pendiente |
| Rate limiting | auth/preview/system operator tables | negative matrix pendiente |
| File security | MIME, checksum, ClamAV, private storage | provider E2E pendiente |
| Stripe | signature/dedup/service amount | deterministic provider activo; Stripe profile pendiente |
| Secret handling | ignored `.env.local`; no valores registrados | pass source hygiene; scans pendientes |

## Riesgos P0/P1 baseline

1. Host mismatch rompe autenticación, pero la solución no será wildcard CORS ni Origin bypass.
2. Helpers E2E permiten Forbidden como falso éxito.
3. Function grant faltante provoca fallo periódico del change-feed retention.
4. Schedulers siguen activos en ciertos tests y generan errores después de destruir Testcontainers; puede ocultar ruido real.
5. UI expone campos UUID en promociones/readiness; riesgo de errores/BOLA aunque backend autorice.
6. Providers “healthy” no equivalen a MinIO/ClamAV/Stripe ejercitados.
