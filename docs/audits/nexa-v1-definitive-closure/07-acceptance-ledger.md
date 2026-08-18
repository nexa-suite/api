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
| 12 | Receivables/payments/Stripe | CLOSED | `stripe-java` oficial ejercitado contra el provider activo `stripe` y WireMock (create/retrieve/confirm); PaymentIntent `REQUIRES_ACTION` → confirmación → webhook HMAC firmado/inbox/worker → `SUCCEEDED`; receivable `PAID` y recibo PDF generado/descargable |
| 13 | PostgreSQL/Flyway/RLS | CLOSED | V64–V68, fresh, `validate`, upgrade V63→V68 con fila histórica preservada y RLS PASS |
| 14 | REST/OpenAPI | CLOSED | runtime export 223 paths, +10 sin removals, contratos frontend alineados |
| 15 | Security hardening | CLOSED | integración security/RLS/CORS PASS; DAST ZAP autenticado remoto por seis roles `6/6` con `FAIL-NEW:0` (`WARN-NEW:6–7`, `PASS:110–111`) y baseline `FAIL-NEW:0` en Security/Load `31255791245`; Trivy/SBOM Supply Chain `31255791246` sin vulnerabilidades, secretos ni misconfiguraciones; no se declara certificación ASVS |
| 16 | Performance/observability | CLOSED | reads HTTP bajo objetivos, query budgets 1/10/50 PASS, traza `nexa-api` en Jaeger; Security/Load `31255791245`: k6 service `720/720`, p95 `405.65 ms`, p99 `514.42 ms`; matriz negocio `3567/3567`, p95 `68.67 ms`, p99 `96.41 ms`, 0% `http_req_failed`; local matriz `7472/7472`, p95 `8.36 ms`, p99 `11.62 ms` |
| 17 | Automated/browser acceptance | CLOSED | flujo autenticado completo y E2E remoto Platform `52/52` + Portal `16/16`; sesión Portal local `nexa-stripe` montó Payment Element contract-compatible, POST de confirmación `200`, fila de receivable `PAID` y descarga de receipt PDF válida |
| 18 | Conditional presentation advance | CLOSED | todos los gates funcionales de foundation están demostrados; Stripe externo productivo/credenciales de cuenta permanecen fuera de alcance explícito del prompt |

## Baseline test ledger

| Area | Tests | Passed | Failed | Skipped | Interpretación |
|---|---:|---:|---:|---:|---|
| API integración obligatoria | 333 | 333 | 0 | 0 | `./mvnw clean verify -Dnexa.integration.enabled=true`, Testcontainers/PostgreSQL 18.4 + Stripe mock + ClamAV; local final 2026-08-08 |
| API default | 333 | 333 | 0 | 86 | suite completa; skips son clases condicionadas no obligatorias |
| Platform unit | 98 | 98 | 0 | 0 | 52 archivos |
| Portal unit | 76 | 76 | 0 | 0 | 39 archivos |
| Platform browser E2E remoto | 52 | 52 | 0 | 0 | CI run `31250830175`, desktop + mobile |
| Portal browser E2E remoto | 16 | 16 | 0 | 0 | CI run `31250833691`, desktop + mobile |
| Docker/browser local | — | PASS | 0 | — | provider `stripe` activo contra WireMock; flujo autenticado de pago y receipt PDF verificado en Portal |

## Definition of Done guard

La foundation funcional queda cerrada para los gates marcados `CLOSED`. El runtime local selecciona explícitamente `NEXA_PAYMENTS_PROVIDER=stripe`; el adapter oficial usa el endpoint Stripe-compatible WireMock y la aceptación browser atraviesa el mismo contrato PaymentIntent/webhook que producción, con firma, deduplicación, settlement y receipt. La prueba contra una cuenta Stripe externa y sus credenciales no forma parte del alcance autorizado por el prompt. Para el commit final `d6fa950`, API CI `31255791257`, Security/Load `31255791245` y Supply Chain `31255791246` terminaron `success`; Platform y Portal conservan sus workflows verdes previamente verificados. No se crea tag ni Release por la política del prompt.
