# Database migration ledger

## Baseline y cierre actual

- Baseline auditado: `V63__bind_payment_webhooks_to_tenant_scope.sql`.
- Cierre actual: `V68__allow_stripe_sales_order_payment_option.sql`.
- Total: 68 migraciones append-only e inmutables.
- Migración fresca obligatoria: 68 validadas/aplicadas sobre PostgreSQL 18.4.
- Suite de migración verificó grants de `nexa_runtime`, permisos operativos de Company Owner y las tablas/columnas canónicas de V65.
- Upgrade desde snapshot V63: PASS en `ModernPostgresUpgradeMigrationTests`; PostgreSQL 18.4 arranca en V63, conserva una fila histórica de catálogo y aplica V64–V68 hasta la versión 68.

## Bloques de autoridad

| Versiones | Autoridad principal |
|---|---|
| V1–V9 | IAM, tenant/workspace, refresh sessions, throttling |
| V10–V14 | Client Accounts, Purchase Requests, Sales Orders, change feed |
| V15–V22 | Warehouse, reservations, Logistics, tracking/evidence |
| V23–V28 | roles, password reset, onboarding, security hardening |
| V29–V35 | tenant configuration, Catalog, idempotency, promotion priority |
| V36–V39 | commerce foundations, owner access, warehouse coordinates, canonical permissions |
| V40–V41 | Product Families/SKUs, canonical buyer drafts |
| V42–V43 | Business Documents/outbox, receivables/payments |
| V44–V58 | RLS, snapshots, constraints, retries, query indexes, document/evidence lifecycle |
| V59–V63 | runtime actor, payment/RLS hardening, IAM/audit/reference grants, bank transfer/webhook scope |
| V64 | runtime change-feed retention grant para `nexa_runtime` |
| V65 | jerarquía canónica Product Family → Product Variant → Sellable SKU, mapping y bootstrap |
| V66 | retirada de permisos operativos de Company Owner |
| V67 | grants runtime para product variants |
| V68 | opción `CARD_STRIPE` y check constraint de método de pago |

## Defectos y correcciones confirmadas

`V14` ejecuta:

```sql
GRANT EXECUTE ON FUNCTION integration.purge_expired_change_events(INTEGER) TO CURRENT_USER;
```

Flyway usa el migrador; la app usa `nexa_runtime`. `V59` concede schemas/tables/sequences, pero no esta función. Resultado: scheduler falla con `permission denied for function purge_expired_change_events`.

Corrección aplicada: `V64`, nueva migración append-only condicionada a existencia de `nexa_runtime`, con test de grants/ejecución. `V14` permanece inmutable.

El flujo FEFO también normaliza la unidad comercial (`upper(line.unit)`) antes de comparar contra inventario; evita que una línea `unit` no genere una reserva falsa por diferencia de casing.

## Gates ejecutados y pendientes

- `Flyway validate` y migración fresca completa: PASS (68/68).
- Grants de runtime y funciones: PASS (`nexa_runtime` login, no superuser/bypass RLS/create DB/create role).
- RLS por tenant/workspace y forced policies: PASS en suite y runtime para tablas sensibles.
- Concurrencia FEFO, conversión, webhooks y seguridad: PASS en integración obligatoria (333/333, 0 skips).
- Upgrade migration desde V63 con datos preservados: PASS (1/1, 0 fallos, 0 skips).
- Índices/query budgets: no se ejecutó una medición final p50/p95/p99; no se marca como PASS.
