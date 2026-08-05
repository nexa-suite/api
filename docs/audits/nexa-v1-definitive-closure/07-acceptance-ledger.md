# Acceptance ledger

Actualizado tras cada subtask. `OPEN` no significa ausencia de código; significa gate completo sin demostrar.

| # | Subtask | Estado | Evidencia / defectos abiertos |
|---:|---|---|---|
| 0 | Skills, baseline, parity map | CLOSED | inventarios, 124 Stories, rutas, consumidores, migraciones, 2 contact sheets y auto-audit verificados |
| 1 | Docker, same-origin, IAM P0 | OPEN | Portal CORS, Platform root, runtime function grant |
| 2 | Canonical routes | OPEN | aliases y Edit Request |
| 3 | IAM/tenant/permission authority | OPEN | persona/session/authorization matrix |
| 4 | Product Family/Variant/SKU | OPEN | audit DDD + concrete Gouda proof |
| 5 | Client Accounts/address/geography | OPEN | UX UUID-free + maps boundary |
| 6 | Request Builder | OPEN | revision/edit/autosave flow |
| 7 | Sales/manual orders | OPEN | browser/API workflow |
| 8 | Warehouse/FEFO | OPEN | concurrency + no typed UUID |
| 9 | Logistics/POD | OPEN | lifecycle + evidence + temperature |
| 10 | Event backbone/process manager | OPEN | shared ownership hotspot |
| 11 | Documents/storage/security | OPEN | missing Platform actions + provider gates |
| 12 | Receivables/payments/Stripe | OPEN | provider deterministic baseline |
| 13 | PostgreSQL/Flyway/RLS | OPEN | V64+ grant, upgrade/RLS gates |
| 14 | REST/OpenAPI | OPEN | runtime/static semantics |
| 15 | Security hardening | OPEN | ASVS/DAST/secret scans |
| 16 | Performance/observability | OPEN | p50/p95/p99/query budgets |
| 17 | Automated/browser acceptance | OPEN | correlated Buyer-to-Cash |
| 18 | Conditional presentation advance | BLOCKED BY POLICY | no visual polish before P0/P1 green |

## Baseline test ledger

| Area | Tests | Passed | Failed | Skipped | Interpretación |
|---|---:|---:|---:|---:|---|
| API default | 327 | 327 | 0 | 80 | no integración obligatoria |
| Platform unit | 97 | 97 | 0 | 0 | source/unit only |
| Portal unit | 73 | 73 | 0 | 0 | source/unit only |
| Docker browser | — | 0 | P0 | — | Portal/Platform reproducidos rotos |

## Definition of Done guard

No cerrar foundation mientras falte cualquiera: zero mandatory skips, providers activos, route crawler, persona matrix, security/concurrency, runtime/static OpenAPI, clean Git, remote CI y flujo Buyer→Payment Receipt correlacionado.
