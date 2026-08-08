# Acceptance ledger

Actualizado tras cada subtask. `OPEN` no significa ausencia de código; significa gate completo sin demostrar.

| # | Subtask | Estado | Evidencia / defectos abiertos |
|---:|---|---|---|
| 0 | Skills, baseline, parity map | CLOSED | inventarios, 124 Stories, rutas, consumidores, migraciones, 2 contact sheets y auto-audit verificados |
| 1 | Docker, same-origin, IAM P0 | CLOSED | stack healthy, exact CORS, runtime grant y browser autenticado |
| 2 | Canonical routes | CLOSED | aliases, typed routes y Edit Request |
| 3 | IAM/tenant/permission authority | CLOSED | roles, permissions, authorization version y session matrix en integración |
| 4 | Product Family/Variant/SKU | CLOSED | mapping canónico, 66 variants/132 SKUs y prueba Gouda |
| 5 | Client Accounts/address/geography | CLOSED | selectors server-backed y rutas tipadas; sin UUID manual en flujo |
| 6 | Request Builder | CLOSED | draft/edit/submit browser proof |
| 7 | Sales/manual orders | CLOSED | review/approve/convert/confirm en browser |
| 8 | Warehouse/FEFO | CLOSED | reservation, allocation FEFO y shortage 0 en browser |
| 9 | Logistics/POD | CLOSED | dispatch lifecycle, temperature, incident, reprogram y POD |
| 10 | Event backbone/process manager | CLOSED | outbox publicado para confirmación, fulfillment, delivery y POD |
| 11 | Documents/storage/security | PARTIAL | PDF/CSV/MinIO/checksum/download PASS; ClamAV/XML y cross-tenant provider matrix pendientes |
| 12 | Receivables/payments/Stripe | PARTIAL | opción CARD_STRIPE y contratos base; Stripe SDK/provider/Portal Payment Element pendiente |
| 13 | PostgreSQL/Flyway/RLS | PARTIAL | V64–V68, fresh/validate/RLS PASS; upgrade V63 independiente pendiente |
| 14 | REST/OpenAPI | CLOSED | runtime export 223 paths, +10 sin removals, contratos frontend alineados |
| 15 | Security hardening | PARTIAL | integración security/RLS/CORS PASS; DAST y secret scan final no ejecutados |
| 16 | Performance/observability | PARTIAL | perfiles/health/Jaeger/OTEL activos; p50/p95/p99 final pendiente |
| 17 | Automated/browser acceptance | CLOSED | buyer→sales→warehouse→logistics→POD→tracking y documentos correlacionados |
| 18 | Conditional presentation advance | BLOCKED BY POLICY | no se habilita polish hasta cerrar PARTIAL provider/upgrade/performance/security gates |

## Baseline test ledger

| Area | Tests | Passed | Failed | Skipped | Interpretación |
|---|---:|---:|---:|---:|---|
| API integración obligatoria | 329 | 329 | 0 | 0 | `-Dnexa.integration.enabled=true`, Testcontainers/PostgreSQL 18.4 |
| API default | 329 | 329 | 0 | 80 | suite completa; skips son clases condicionadas no obligatorias |
| Platform unit | 98 | 98 | 0 | 0 | 52 archivos |
| Portal unit | 74 | 74 | 0 | 0 | 38 archivos |
| Docker/browser | — | PASS | 0 | — | flujo autenticado y capturas actuales |

## Definition of Done guard

La foundation funcional queda cerrada para los gates marcados `CLOSED`. No se declara cierre/publicación total porque siguen `PARTIAL`: Stripe real/Payment Element, ClamAV/XML/cross-tenant provider matrix, upgrade V63, DAST/secret scan y performance p50/p95/p99; además no se ejecutaron push, CI remoto, tag ni Release.
