# API consumer map

## Topología baseline

| Surface | Config actual | Consumidores | Problema |
|---|---|---:|---|
| Platform | default `http://localhost:8080` | 35 archivos / 131 llamadas | rompe Docker `127.0.0.1`, omite proxy same-origin |
| Portal | default `http://localhost:8080` | 31 archivos / 56 llamadas | CSP/CORS/cookie host mismatch |
| Nginx Platform | `/api/ → api:8080` | proxy existe | bundle no lo usa; `Host $host` pierde puerto |
| Nginx Portal | `/api/ → api:8080` | proxy existe | bundle no lo usa; `Host $host` pierde puerto |

## Bounded consumer ownership

| Context | Platform consumer | Portal consumer | API authority |
|---|---|---|---|
| IAM | `AuthApiService`, `SecurityApiService` | `PortalAuthApiClient`, `SecurityApiClient` | Authentication/IamSecurity controllers |
| Tenant Management | `CompanyAdministrationApiService` | buyer account projection | Organization/Tenant/Role controllers |
| Catalog | catalog query/management services | `CatalogApiClient` | Catalog controllers |
| Sales | operations, PR, SalesOrder, manual draft clients | buyer draft/request/order clients | Sales controllers |
| Warehouse | `WarehouseOperationsApiService` | availability client | Warehouse controller |
| Logistics | `LogisticsApiService` | delivery tracking client | Logistics controller |
| Documents | `BusinessDocumentsApiService` | `BusinessDocumentsApiClient` | BusinessDocument controller |
| Payments | platform review paths embedded in operations | `PaymentsApiClient` | Payment controller |
| Notifications | Platform notification service | notifications client | Notification controller |
| Audit/change feed | Audit API + change feed | change feed | Audit/ChangeFeed controllers |

## Hallazgos baseline

1. `PortalAuthApiClient.workspacePreview` no usa `withCredentials`; correcto para preview público, pero toda falla se convertía en `recognized=false`, ocultando CORS/red/timeout/5xx/429.
2. `buyer-request-api.client.ts` conserva endpoints `/buyer-requests` y `/buyer-requests/previews` que no están en el OpenAPI 0.9.0; debe comprobarse si es código muerto/duplicado frente a `CanonicalPurchaseRequestDraftApiClient`.
3. El UI de promociones recibe IDs internos como texto; no hay selector por recursos.
4. Fulfillment readiness pide `salesOrderId` y version manuales; viola el contrato “ningún UUID interno escrito por usuario”.
5. Business Documents dispone de ports de generación/evidencia, pero el componente Platform solo lista/descarga.
6. Payment Methods es honesto y enlaza receivables; el provider activo baseline es `deterministic`, no Stripe.

Gate final: cada endpoint consumido debe existir en OpenAPI runtime/estático, toda ruta visible debe disparar una acción real y ninguna UI debe solicitar UUID interno.
