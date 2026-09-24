# API foundation verification — 2026-09-24

These are local technical results on macOS arm64. They do not constitute
Product Acceptance, System Acceptance, or the Blueprint Production Gate.

| Gate | Result | Evidence |
| --- | --- | --- |
| Structural differential | Observed regression corrected | [Develop/refactor comparison](./api-structure-differential-2026-09-24.md): 144 additional application-context errors on the original refactor were caused by duplicate `NotificationUseCase` candidates; the shared object-storage test dependency was made disposable. |
| Full API verification | PASS | `./mvnw clean verify -Dnexa.integration.enabled=true`: 486 tests, 0 failures, 0 errors, 0 skipped. Host Oracle JDK 26.0.1 compiled with `--release 25`; the container build ran on Temurin 25.0.4. |
| Database bootstrap | PASS for empty disposable PostgreSQL | PostgreSQL 18.4 started empty; Flyway V1–V100 ran at API startup with the migrator; the app then reached readiness with a separate non-owner, non-`BYPASSRLS` runtime login. Supported baseline upgrade was not exercised. |
| RLS inventory | Coverage OPEN | [V100 table inventory](../security/rls-current-schema-audit.md): 165 tables classified, 91 with forced RLS, 50 Tenant or inherited-scope tables without their own policy. The inventory test and restricted-role integration tests passed; this is not complete RLS coverage. |
| OpenAPI | PASS for tested parity | `OpenApiContractIT` passed; the committed snapshot compatibility check found no breaking change against `origin/develop`. Integrated Website/Web/Mobile acceptance remains separate. |
| Web shared HTTP layer | PASS for local unit contracts | `npm run test:api` in `web-clients` passed 9 tests across 3 files. Platform and Portal have shell applications but no business route smoke yet. |
| Operations Mobile auth/network | PASS for local unit contracts | `./gradlew :core:auth:testDebugUnitTest :core:network:testDebugUnitTest --no-daemon --console=plain` in `mobile/apps/operations-android`; [consumer boundary](./cross-client-contract-2026-09-24.md). |
| Docker and Compose | PASS locally | `docker compose --env-file .env.local -f ops/compose/modern.compose.yml config --quiet`; final multi-stage API image built, with a non-root runtime user and health check. |
| Isolated HTTP smoke | PASS for exercised routes | Fresh disposable PostgreSQL and final API image: readiness `200`/`UP`; unauthenticated `GET /api/v1/session` `401`; invalid public contact `400`; valid demo contact `202`/`RECEIVED`, one row persisted. Containers and network were removed after the run. No existing local database was modified. |
| Remote security/load | PASS on `32ddade` | [GitHub run 36061315883](https://github.com/nexa-suite/api/actions/runs/36061315883) passed. The 20-second, 4-VU service smoke sent 640 requests with 640 checks passed, 0 HTTP failures, p95 478.0 ms / p99 499.3 ms; the business command smoke sent 2,630 requests with all checks passed, p95 109.2 ms / p99 158.6 ms. Authenticated role scans and baseline ZAP reported 0 FAIL-NEW findings, with low/informational warnings. This short test is not a production capacity target or performance certification. |
| Remote supply chain | PASS on `32ddade` | [GitHub run 36061315843](https://github.com/nexa-suite/api/actions/runs/36061315843) passed filesystem and container jobs, including SBOM/provenance generation and high/critical image scan. |
| Remote API CI | PASS on `32ddade` | [GitHub run 36061316028](https://github.com/nexa-suite/api/actions/runs/36061316028) passed all 486 tests and OpenAPI compatibility. The earlier [run 36058575688](https://github.com/nexa-suite/api/actions/runs/36058575688) errored because Docker Hub denied the old MinIO image pull on a clean runner. The test fixture now uses a pullable LocalStack S3 container. |
| CI workflow syntax | PASS | `actionlint .github/workflows/*.yml`. The existing Supply Chain workflow builds an attested image, emits an SBOM, and gates source/image Trivy scans on high or critical findings. |

## Container and supply chain snapshot

The previous pinned runtime image produced 192 Ubuntu package findings (167
medium, 25 low), 160 of which had fixes. The final build uses refreshed,
digest-pinned Temurin 25 build/runtime bases and applies available Ubuntu
security updates in the runtime stage. Trivy 0.74.0 on the final local image
`sha256:8fca040b90936b1ceec552cb7cd10d675bef202cac8e5ddaf0e0d47f7b3c8ae1`
reported **32 OS package findings: 28 medium, 4 low, 0 high, 0 critical**;
none had a listed fix at scan time. It reported 0 Java package vulnerabilities,
0 image secret findings, and 0 image misconfigurations. This is a point-in-time
scan, not a permanent vulnerability waiver.

Trivy produced a local CycloneDX SBOM with 278 components. It was not signed,
published, or attached to a release. Local source scanning encountered only
ignored developer secret files; tracked source was scanned separately. Secret
values are intentionally absent from this record.

The [production-gate requirements](../operations/production-gate-requirements.md)
list unresolved provider, recovery, retention, telemetry, and response
decisions. No performance benchmark, supported-database upgrade rehearsal,
integrated new Web client smoke, or live Android-to-API smoke was performed.
The local Compose stack still references a MinIO image that a clean Docker Hub
runner cannot pull; Compose configuration validation is not a clean-stack
startup result.
The authenticated ZAP workflow now uploads only k6 metric summaries; full ZAP
traffic and API runtime logs stay in the ephemeral runner and are not uploaded
as artifacts.
