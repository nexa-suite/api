# Legacy → Modern parity

Baseline de Subtask 0. Estados: `complete-source`, `partial`, `broken-runtime`, `superseded`, `out-of-scope`.

## Superficies Legacy visibles

| Legacy capability | Modern route/source | Autoridad | Estado baseline | Defecto comprobado |
|---|---|---|---|---|
| Sales Dashboard | `/ops/commercial/dashboard` | Sales API | broken-runtime | sesión/root en Forbidden |
| Product Catalog | `/ops/catalog/products`; alias `/ops/product-catalog` | Catalog Management | partial | alias existe; target selection de promociones exige UUID |
| Purchase Requests | `/ops/commercial/purchase-requests` | Sales | broken-runtime | IAM Docker |
| Purchase Request Detail | `/ops/commercial/purchase-requests/:id` | Sales | broken-runtime | IAM Docker |
| Purchase Orders | `/ops/commercial/sales-orders` | SalesOrder | partial | falta alias Legacy `purchase-orders` |
| Purchase Order Detail | `/ops/commercial/sales-orders/:id` | SalesOrder | partial | falta alias Legacy `purchase-orders/:id` |
| Manual Order Entry | `/ops/commercial/manual-orders/*` | Sales | partial | falta alias `manual-order-entry`; wizard real sí existe |
| B2B Clients | `/ops/commercial/client-accounts` | Sales | broken-runtime | IAM Docker |
| Client Detail | `/ops/commercial/client-accounts/:id` | Sales | broken-runtime | IAM Docker |
| Promotions | `/ops/catalog/promotions` | Catalog Management | partial | falta alias `/ops/commercial/promotions`; IDs internos visibles |
| Business Documents | `/ops/operations/business-documents` | Business Documents | partial | listado/download sí; generation/upload/regeneration/detail no expuestos |
| Operations Dashboard | `/ops/operations/dashboard` | Warehouse/Logistics projections | broken-runtime | IAM Docker |
| Inventory Control | `/ops/operations/inventory` | Warehouse | partial | falta alias `inventory-control` |
| Inventory Lots | `/ops/operations/inventory/lots` | Warehouse | partial | falta alias `inventory-lots` |
| Dispatch Board | `/ops/operations/dispatch-orders` | Logistics | broken-runtime | IAM Docker |
| Dispatch Detail | `/ops/operations/dispatch-orders/:id` | Logistics | broken-runtime | IAM Docker |
| Proof of Delivery | `/ops/operations/proof-of-delivery` | Logistics | complete-source | browser pendiente |
| Operational Analytics | `/ops/operations/operational-analytics` | Logistics | complete-source | browser pendiente |
| Company Administration | `/ops/operations/company-administration` | Tenant Management | broken-runtime | IAM Docker |
| Profile | `/iam/profile` | IAM | partial | falta alias `/ops/profile` |
| Buyer Home | `/portal/home` | Buyer projections | broken-runtime | Portal no reconoce workspace en `127.0.0.1` |
| Buyer Product Catalog | `/portal/product-catalog` | Catalog Management | broken-runtime | IAM Docker |
| Product Detail | `/portal/product-catalog/:id` | Catalog Management | broken-runtime | IAM Docker |
| Request Builder | `/portal/request-builder` | canonical draft API | broken-runtime | IAM Docker |
| My Requests | `/portal/purchase-requests` | Sales | broken-runtime | IAM Docker |
| Request Detail | `/portal/purchase-requests/:id` | Sales | broken-runtime | IAM Docker |
| Edit Request | `/portal/purchase-requests/:id/edit` | Sales draft/revision policy | partial | redirect genera ruta inexistente `/request-builder/:id` |
| My Orders | `/portal/sales-orders` | SalesOrder | partial | falta alias canonical `purchase-orders` |
| Order Success | — | SalesOrder | missing | falta ruta/redirect honesto |
| Order Detail | `/portal/sales-orders/:id` | SalesOrder | partial | falta alias `purchase-orders/:id` |
| Tracking | `/portal/deliveries/:id` | Logistics | complete-source | browser pendiente |
| Documents | `/portal/documents` | Business Documents | complete-source | browser pendiente |
| Payment Methods | `/portal/payment-methods` | Payments | superseded | página enlaza flujos reales; no simula tarjeta guardada |
| Premium | — | Feature policy | missing | requiere feature gate funcional |
| Profile | `/portal/profile` | IAM | complete-source | browser pendiente |
| Terms | `/portal/legal` | legal view | partial | falta alias `/portal/legal/terms` |
| Privacy | `/portal/legal` | legal view | partial | falta alias `/portal/legal/privacy` |
| Support | `/portal/support` | support view | complete-source | browser pendiente |

## Trazabilidad de User Stories

La tabla enumera **cada** Story vigente. `blocked-runtime` significa que hay consumidor/use case persistente, pero el gate de browser todavía no es válido.

| IDs | Modern disposition | Estado baseline |
|---|---|---|
| US01–US06 | Website canónica | out-of-scope explícito |
| US07 | IAM login + workspace preview + session | broken-runtime |
| US08 | validación de formularios Platform/Portal | complete-source |
| US09 | password reset + Mailpit | complete-source; browser pendiente |
| US10 | onboarding organization, paso empresa/RUC | complete-source; browser pendiente |
| US11 | onboarding operation/cold-chain | complete-source; browser pendiente |
| US12 | onboarding warehouse/location | complete-source; browser pendiente |
| US13 | onboarding administrator | complete-source; browser pendiente |
| US14 | onboarding workspace/slug/plan | complete-source; browser pendiente |
| US15 | registration submission/terms | complete-source; browser pendiente |
| US16 | pending registration + activation boundary | complete-source; browser pendiente |
| US17 | Company overview | complete-source; browser pendiente |
| US18 | organization profile mutation | complete-source; browser pendiente |
| US19 | workspace list/configuration | complete-source; browser pendiente |
| US20 | membership list | complete-source; browser pendiente |
| US21 | invitation-based membership creation | complete-source; browser pendiente |
| US22 | membership suspend/reactivate/roles | complete-source; browser pendiente |
| US23 | role definitions + access matrix | complete-source; browser pendiente |
| US24 | operational settings read | complete-source; browser pendiente |
| US25 | operational settings mutation | complete-source; browser pendiente |
| US26 | custom-field definitions read | complete-source; browser pendiente |
| US27 | custom-field create/edit/lifecycle | complete-source; browser pendiente |
| US28 | plan usage projection | complete-source; browser pendiente |
| US29 | plan comparison projection | complete-source; browser pendiente |
| US30 | reference-only plan review | superseded honest read-only policy |
| US31 | regional settings | complete-source; browser pendiente |
| US32 | unit preferences | complete-source; browser pendiente |
| US33 | workspace notification preferences | complete-source; browser pendiente |
| US34 | workspace behavior | complete-source; browser pendiente |
| US35 | tenant security settings | complete-source; browser pendiente |
| US36 | own profile read | complete-source; browser pendiente |
| US37 | own profile update | complete-source; browser pendiente |
| US38 | notification center/read state | complete-source; browser pendiente |
| US39 | Buyer home | blocked-runtime |
| US40 | Buyer catalog | blocked-runtime |
| US41 | catalog search/filter | blocked-runtime |
| US42 | catalog detail | blocked-runtime |
| US43 | pricing/promotion preview | blocked-runtime |
| US44 | draft line add/remove | blocked-runtime |
| US45 | empty draft prevention | complete-source; browser pendiente |
| US46 | address/delivery/preferences | blocked-runtime |
| US47 | route preview | blocked-runtime |
| US48 | payment preference snapshot | blocked-runtime |
| US49 | draft review | blocked-runtime |
| US50 | idempotent draft submission | blocked-runtime |
| US51 | buyer request list | blocked-runtime |
| US52 | buyer request detail | blocked-runtime |
| US53 | request events/observations | complete-source; browser pendiente |
| US54 | cancellation/rejection state | complete-source; browser pendiente |
| US55 | buyer SalesOrder list | complete-source; route aliases pendientes |
| US56 | buyer delivery tracking | complete-source; browser pendiente |
| US57 | buyer documents/download | complete-source; browser pendiente |
| US58 | receivables/payment initiation | complete-source; provider gate pendiente |
| US59 | payment methods | superseded by server-authoritative intent/transfer/credit flows; browser pendiente |
| US60 | catalog CRUD/pricing/visibility | partial: UUID target UX pendiente |
| US61 | sales catalog search/filter | complete-source; browser pendiente |
| US62 | promotion creation | partial: internal IDs visibles |
| US63 | promotion edit/pause | complete-source; browser pendiente |
| US64 | promotion lifecycle | complete-source; browser pendiente |
| US65 | sales dashboard | blocked-runtime |
| US66 | purchase request inbox | blocked-runtime |
| US67 | account/RUC/credit snapshot | complete-source; browser pendiente |
| US68 | inventory availability | complete-source; browser pendiente |
| US69 | changes-requested workflow | complete-source; browser pendiente |
| US70 | purchase request rejection | complete-source; browser pendiente |
| US71 | approval + SalesOrder conversion | complete-source; integration pendiente |
| US72 | SalesOrder list | complete-source; alias pendiente |
| US73 | SalesOrder detail/events | complete-source; browser pendiente |
| US74 | manual order client step | complete-source; alias pendiente |
| US75 | manual order items | complete-source; browser pendiente |
| US76 | manual order delivery/priority | complete-source; browser pendiente |
| US77 | idempotent manual order submission | complete-source; integration pendiente |
| US78 | client account list | complete-source; browser pendiente |
| US79 | client account creation | complete-source; browser pendiente |
| US80 | client edit/suspend | complete-source; browser pendiente |
| US81 | warehouse dashboard | blocked-runtime |
| US82 | inventory overview/lots | complete-source; aliases pendientes |
| US83 | FEFO lot ordering | complete-source; integration pendiente |
| US84 | inbound receipt/movements | complete-source; browser pendiente |
| US85 | waste with reason | complete-source; browser pendiente |
| US86 | inventory reservations | partial: readiness screen pide UUID/version |
| US87 | warehouse promotion authority | superseded by Catalog Promotion aggregate |
| US88 | dispatch Kanban | blocked-runtime |
| US89 | persisted dispatch lifecycle | complete-source; browser pendiente |
| US90 | route start | complete-source; browser pendiente |
| US91 | delivered dispatch list | complete-source; browser pendiente |
| US92 | POD/evidence | complete-source; integration/browser pendientes |
| US93 | temperature readings | complete-source; browser pendiente |
| US94 | incident workflow | complete-source; browser pendiente |
| US95 | operational analytics | complete-source; browser pendiente |
| US96 | document queue | complete-source; browser pendiente |
| US97 | manual evidence upload | partial: API existe, Platform no expone upload |
| US98 | invoice XML reference generation | complete-source; end-to-end pendiente |
| US99 | dispatch guide PDF | complete-source; end-to-end pendiente |
| US100 | regeneration | partial: API existe, UI no expone acción |
| US101 | document detail/logs | partial: API existe, UI solo lista |
| US102 | logistics document queue | complete-source; browser pendiente |
| US103 | typed PDF/XML generation | partial: worker/renderers existen; UI/flow pendiente |
| US104 | Sales own profile | complete-source; browser pendiente |
| US105 | Sales language preference | partial: preference existe; conmutación runtime por probar |
| US106 | Logistics own profile | complete-source; browser pendiente |
| US107 | Logistics language preference | partial: preference existe; conmutación runtime por probar |

## Technical Stories

| IDs | Modern authority | Estado baseline |
|---|---|---|
| TS01 | IAM REST/session | broken-runtime |
| TS02 | memberships/invitations/profile | complete-source |
| TS03 | organization registration | complete-source |
| TS04 | organization/workspace/settings | complete-source |
| TS05 | client accounts/addresses | complete-source |
| TS06 | Catalog Management REST | complete-source |
| TS07 | category aggregate REST | complete-source |
| TS08 | brand aggregate REST | complete-source |
| TS09 | SalesOrder REST; compatibility aliases pendientes | partial |
| TS10 | credit account/reservation/payment foundation; no legacy credit-request aggregate | partial; decisión de alcance por cerrar |
| TS11 | immutable Business Documents + receivables; no claim SUNAT | partial E2E |
| TS12 | Payments aggregate/providers/webhooks | partial provider E2E |
| TS13 | DispatchOrder sustituye Shipment | superseded, complete-source |
| TS14 | Warehouse REST | complete-source |
| TS15 | inventory/reservation/FEFO REST | complete-source; concurrency pendiente |
| TS16 | audit log REST | complete-source |
| TS17 | reference geography/operations REST | complete-source |
