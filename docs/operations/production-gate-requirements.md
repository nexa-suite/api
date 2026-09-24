# Production-gate runtime requirements

Status: implementation handoff only. The Blueprint Production Gate is open;
this document selects no provider, recovery objective, retention period, SLO,
or responder organization.

## Runtime and deployment interfaces

| Boundary | Current implementation | Production decision and proof needed |
| --- | --- | --- |
| HTTP API | Spring Boot container, non-root `nexa` user, Actuator liveness/readiness health checks | Ingress, TLS, allowed origins, trusted proxy configuration, startup and shutdown smoke. |
| PostgreSQL | Flyway V1–V100, separate migrator and restricted runtime login, transaction-local Tenant/Workspace scope | Managed service and version, role provisioning, migration job, least-privilege grants, complete RLS coverage decision, upgrade and restore rehearsal. |
| Private objects | S3-compatible adapter; disposable LocalStack S3 emulator in integration tests | Provider, private bucket policy, encryption, key lifecycle, object restore and retention. |
| Email | SMTP adapter and security notification outbox | Provider, sender/domain verification, delivery failure handling, credential rotation. |
| Malware scan | ClamAV network adapter and local deterministic mode | Scanner operation, signatures, availability policy, failure and quarantine exercises. |
| Payments | Disabled/deterministic local path and Stripe-compatible adapter/webhook | Provider/account setup, webhook secret rotation, replay and failure exercise, reconciliation ownership. |
| Maps | Local and provider adapter configuration | Provider choice, credential handling, availability and quota behavior. |
| Telemetry | Health, metrics and optional OTLP profile | Backend, sampling/retention, alert thresholds, dashboard and responder ownership. |

Configuration entry points are `src/main/resources/application*.yml` and
`ops/compose/modern.compose.yml`. The local Compose profile and its sample
secrets are development infrastructure, not a production deployment template.
Its current MinIO Docker Hub image is unavailable to a clean runner; replacing
that local service requires a plan that preserves existing local object data.
Required secret categories include database runtime/migrator credentials, JWT
signing material, throttling and notification encryption keys, SMTP, object
storage, payment webhook/API credentials, and optional maps credentials. A
production secret manager, rotation procedure, access policy, and audit trail
remain to be selected and exercised.

## Release and recovery evidence to collect

1. Provision an empty PostgreSQL instance, run all Flyway migrations using the
   migrator role, then start the API with the restricted runtime role. Verify
   readiness and one scoped request. Never use the migrator for ordinary API
   traffic.
2. Rehearse an upgrade from a named supported baseline with a data-preserving
   fixture. Record migration time, locks, rollback boundary and application
   compatibility. Do not edit historical migrations.
3. Establish RPO/RTO and backup retention through the Production Gate. Prove a
   restore into an isolated environment, including private objects, credentials
   and replayable outbox/inbox state.
4. Exercise rollout, rollback and worker shutdown with in-flight work, stale
   lease fencing, provider outage, and failed migrations. Define the responder
   and break-glass path before deployment.
5. Connect real logs, metrics, traces and alerts to an owned backend; verify
   correlation, sensitive-data redaction and incident notification.

The [current RLS inventory](../security/rls-current-schema-audit.md) records
50 business or inherited-scope tables without their own RLS policy. Their
per-table policy and worker access design must be reviewed before complete
isolation can be claimed. Client integration evidence is tracked in the
[cross-client inventory](../verification/cross-client-contract-2026-09-24.md).
