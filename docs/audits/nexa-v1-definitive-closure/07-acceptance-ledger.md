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
| 11 | Documents/storage/security | CLOSED | PDF/CSV/XML/MinIO/checksum/download PASS; ClamAV real PASS; lectura/descarga HTTP cross-tenant retorna 404 sin exposición |
| 12 | Receivables/payments/Stripe | PARTIAL | Stripe SDK oficial + WireMock, PaymentIntent, webhook firmado, settlement y recibo PASS; Payment Element monta en Portal; falta confirmación browser contra Stripe test real |
| 13 | PostgreSQL/Flyway/RLS | CLOSED | V64–V68, fresh, `validate`, upgrade V63→V68 con fila histórica preservada y RLS PASS |
| 14 | REST/OpenAPI | CLOSED | runtime export 223 paths, +10 sin removals, contratos frontend alineados |
| 15 | Security hardening | PARTIAL | integración security/RLS/CORS PASS; ZAP API baseline remoto `0` fallos/`7` warnings y Trivy/SBOM `0` findings; falta DAST autenticado por rol y certificación externa |
| 16 | Performance/observability | PARTIAL | reads HTTP medidos bajo objetivos, query budgets 1/10/50 PASS, traza `nexa-api` en Jaeger y k6 remoto `715/715` checks con `0%` errores; falta matriz completa de comandos de negocio |
| 17 | Automated/browser acceptance | PARTIAL | flujo autenticado completo y E2E remoto Platform `52/52` + Portal `16/16`; Payment Element monta, pero falta confirmación browser contra Stripe test real sin credencial externa |
| 18 | Conditional presentation advance | BLOCKED BY POLICY | no se habilita polish hasta cerrar PARTIAL provider/upgrade/performance/security gates |

## Baseline test ledger

| Area | Tests | Passed | Failed | Skipped | Interpretación |
|---|---:|---:|---:|---:|---|
| API integración obligatoria | 333 | 333 | 0 | 0 | `-Dnexa.integration.enabled=true`, Testcontainers/PostgreSQL 18.4 + Stripe mock + ClamAV |
| API default | 333 | 333 | 0 | 86 | suite completa; skips son clases condicionadas no obligatorias |
| Platform unit | 98 | 98 | 0 | 0 | 52 archivos |
| Portal unit | 76 | 76 | 0 | 0 | 39 archivos |
| Platform browser E2E remoto | 52 | 52 | 0 | 0 | CI run `31250830175`, desktop + mobile |
| Portal browser E2E remoto | 16 | 16 | 0 | 0 | CI run `31250833691`, desktop + mobile |
| Docker/browser local | — | PASS | 0 | — | flujo autenticado y capturas actuales |

## Definition of Done guard

La foundation funcional queda cerrada solo para los gates marcados `CLOSED`. Los tres repositorios feature están publicados y los workflows remotos de API (CI, Security and Load, Supply Chain), Platform y Portal están verdes en los SHAs finales verificados. No se declara cierre/publicación total porque siguen `PARTIAL`: confirmación browser contra Stripe test real, DAST autenticado por rol y carga completa de comandos; no se crea tag ni Release por la política del prompt.
