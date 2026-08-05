# Database migration ledger

## Baseline

- Última migración: `V63__bind_payment_webhooks_to_tenant_scope.sql`.
- Total: 63 migraciones inmutables.
- Migración fresca en suite por defecto: 63 validadas/aplicadas sobre PostgreSQL 18.4.
- Upgrade desde snapshot/versión previa: pendiente de gate dedicado.

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

## Defecto runtime confirmado

`V14` ejecuta:

```sql
GRANT EXECUTE ON FUNCTION integration.purge_expired_change_events(INTEGER) TO CURRENT_USER;
```

Flyway usa el migrador; la app usa `nexa_runtime`. `V59` concede schemas/tables/sequences, pero no esta función. Resultado: scheduler falla con `permission denied for function purge_expired_change_events`.

Corrección requerida: nueva migración append-only, condicionada a existencia de `nexa_runtime`, más test de grants/ejecución. No se edita V14.

## Gates pendientes

- Flyway validate.
- Fresh migration completa.
- Upgrade migration desde V63 con datos preservados.
- Grants de runtime y funciones.
- RLS por tenant/workspace y owner-forced.
- Concurrencia FEFO, crédito, conversión y webhook.
- Índices/query budgets.
