# Performance y observabilidad — evidencia local

Fecha: 2026-08-08 (America/Lima). Runtime: Docker `nexa-modern`, API con perfil `local,minio,observability`, PostgreSQL 18.4.

Se ejecutaron 100 lecturas autenticadas secuenciales por endpoint desde el host, descartando respuestas distintas de HTTP 200 y calculando percentiles sobre los tiempos de `curl` (`time_total`). No es un load test concurrente ni sustituye la matriz k6/Gatling de comandos.

| Workflow HTTP | Muestras 200 | p50 | p95 | p99 | Min | Max | Objetivo relevante |
|---|---:|---:|---:|---:|---:|---:|---|
| `GET /api/v1/catalog-items?page=0&size=25` | 100 | 12.8 ms | 24.8 ms | 33.8 ms | 9.7 ms | 179.3 ms | simple read p95 < 300 ms |
| `GET /api/v1/receivables?page=0&size=25` | 100 | 9.4 ms | 16.8 ms | 21.1 ms | 7.4 ms | 27.0 ms | paginated search p95 < 500 ms |

Error rate observado en ambas muestras: 0% (200/200 por endpoint). Query budgets 1/10/50 líneas pasan en `SalesSnapshotQueryBudgetIT` (catálogo `<=4` queries constantes; SKU `1` query).

## k6 service smoke local

`grafana/k6:0.56.0`, 4 VUs durante 20 s contra el Docker runtime: 208 iteraciones, 1.040 requests, checks 100% (`1040/1040`), error rate 0%, p95 `256.04 ms`, p99 `266.33 ms`. Este smoke cubre preview de workspace, login, catálogo, permisos y notificaciones; no sustituye la matriz concurrente de comandos de negocio.

## k6 service smoke remoto

El workflow `API Security and Load` ejecutó el mismo script con 4 VUs/20 s: 143 iteraciones, 715 requests, checks 100% (`715/715`), `http_req_failed` `0.00%`, p95 `421.19 ms` y p99 `473.83 ms`. El artefacto `k6-summary.json` quedó persistido y descargable desde el run final. El smoke cubre preview de workspace, login, catálogo, permisos y notificaciones; no sustituye la matriz concurrente de submit, pricing preview, Warehouse/FEFO, dispatch ni documentos.

## k6 business command matrix local

`grafana/k6:0.56.0`, 4 VUs durante 20 s contra el Docker runtime. Cada VU autenticó Buyer, Sales, Warehouse, Logistics y Tenant Owner, ejecutó un Buyer-to-Cash completo y repitió lecturas y comandos de catálogo, pricing preview, draft/review, Purchase Request, Sales Order manual, inventario/FEFO, reserva idempotente, dispatch, route start, temperatura, documentos, receivable, PaymentIntent y notificaciones. Resultado: 400 iteraciones, `7328/7328` checks, `http_req_failed` `0/7328` (0%), p95 `9.56 ms`, p99 `14.85 ms`. El script queda integrado en `API Security and Load` para repetir esta matriz en CI.

## Trace proof

Con Jaeger `1.76.0` y OTEL collector activos, una llamada autenticada a Catalog produjo servicio `nexa-api`; `GET /api/traces?service=nexa-api&limit=20` devolvió 20 trazas y spans con `otel.status_code=OK`. El perfil local sin `observability` continúa sin exportar OTLP.
