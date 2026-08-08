# Performance y observabilidad — evidencia local

Fecha: 2026-08-08 (America/Lima). Runtime: Docker `nexa-modern`, API con perfil `local,minio,observability`, PostgreSQL 18.4.

Se ejecutaron 100 lecturas autenticadas secuenciales por endpoint desde el host, descartando respuestas distintas de HTTP 200 y calculando percentiles sobre los tiempos de `curl` (`time_total`). No es un load test concurrente ni sustituye la matriz k6/Gatling de comandos.

| Workflow HTTP | Muestras 200 | p50 | p95 | p99 | Min | Max | Objetivo relevante |
|---|---:|---:|---:|---:|---:|---:|---|
| `GET /api/v1/catalog-items?page=0&size=25` | 100 | 12.8 ms | 24.8 ms | 33.8 ms | 9.7 ms | 179.3 ms | simple read p95 < 300 ms |
| `GET /api/v1/receivables?page=0&size=25` | 100 | 9.4 ms | 16.8 ms | 21.1 ms | 7.4 ms | 27.0 ms | paginated search p95 < 500 ms |

Error rate observado en ambas muestras: 0% (200/200 por endpoint). Quedan pendientes las mediciones concurrentes de submit, pricing preview, Warehouse/FEFO, dispatch y generación documental, además de query budgets 1/10/50 líneas.

## Trace proof

Con Jaeger `1.76.0` y OTEL collector activos, una llamada autenticada a Catalog produjo servicio `nexa-api`; `GET /api/traces?service=nexa-api&limit=20` devolvió 20 trazas y spans con `otel.status_code=OK`. El perfil local sin `observability` continúa sin exportar OTLP.
