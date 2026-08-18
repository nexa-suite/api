# Route contract map

## Auth aliases requeridos

| Legacy | Modern actual | Baseline |
|---|---|---|
| `/auth/login` | `/sign-in` | missing alias |
| `/auth/recover` | `/forgot-password` | missing alias |
| `/auth/blocked` | no equivalente explícito | missing honest redirect |
| `/auth/forbidden` | `/forbidden` | missing alias |

## Platform compatibility

| Legacy/canonical | Modern target | Baseline |
|---|---|---|
| `/ops/commercial/dashboard` | mismo | exists |
| `/ops/product-catalog` | `/ops/catalog/products` | exists redirect |
| `/ops/commercial/purchase-requests[/:id]` | mismo | exists |
| `/ops/commercial/purchase-orders[/:id]` | `/ops/commercial/sales-orders[/:id]` | missing |
| `/ops/commercial/manual-order-entry` | `/ops/commercial/manual-orders/new` | missing |
| `/ops/commercial/client-accounts[/:id]` | mismo | exists |
| `/ops/commercial/promotions` | `/ops/catalog/promotions` | missing |
| `/ops/commercial/business-documents[/orders/:orderId]` | documents service view/detail | missing |
| `/ops/operations/dashboard` | mismo | exists |
| `/ops/operations/inventory-control` | `/ops/operations/inventory` | missing |
| `/ops/operations/inventory-lots` | `/ops/operations/inventory/lots` | missing |
| `/ops/operations/dispatch-orders[/:id]` | mismo | exists |
| `/ops/operations/proof-of-delivery` | mismo | exists |
| `/ops/operations/operational-analytics` | mismo | exists |
| `/ops/operations/business-documents[/orders/:orderId]` | list/detail | list exists; detail missing |
| `/ops/operations/company-administration` | mismo | exists |
| `/ops/profile` | `/iam/profile` | missing |

## Portal compatibility

| Legacy/canonical | Modern target | Baseline |
|---|---|---|
| `/portal/home` | mismo | exists |
| `/portal/product-catalog[/:id]` | mismo | exists |
| `/portal/request-builder` | mismo | exists |
| `/portal/purchase-requests[/:id]` | mismo | exists |
| `/portal/purchase-requests/:id/edit` | editable draft/revision | broken redirect |
| `/portal/purchase-orders` | `/portal/sales-orders` | missing |
| `/portal/purchase-orders/success` | post-submit destination | missing |
| `/portal/purchase-orders/:id` | `/portal/sales-orders/:id` | missing |
| `/portal/payment-methods` | mismo | exists |
| `/portal/premium` | feature gate | missing |
| `/portal/profile` | mismo | exists |
| `/portal/legal/terms` | `/portal/legal` section terms | missing |
| `/portal/legal/privacy` | `/portal/legal` section privacy | missing |
| `/portal/support` | mismo | exists |

## Riesgos del router actual

- Platform root usa `RedirectFunction` que resuelve landing antes de que exista sesión restaurada y puede enviar usuario anónimo a Forbidden.
- Wildcards de Platform y Portal ocultan aliases faltantes mediante Home/Sign In.
- Helpers E2E solo comprueban “no Sign In + existe main”; Forbidden puede ser falso verde.
- La ruta Edit Request construye una ruta que no existe y luego cae al wildcard de Home.

Gate: crawler directo + reload + permisos + marcador exacto por ruta, desktop y mobile.
