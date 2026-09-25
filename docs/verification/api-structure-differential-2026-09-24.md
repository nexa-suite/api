# API structure verification — 2026-09-24

## Comparison

Both refs ran in detached worktrees on the same macOS arm64 host, Oracle JDK
26.0.1, Maven Wrapper 3.9.16, and Docker Desktop 29.7.2. The project compiled
with `--release 25`. PostgreSQL integration tests used Testcontainers; local
ClamAV and Stripe mock services were running. The command was identical:

```bash
./mvnw clean verify -Dnexa.integration.enabled=true
```

| Ref | Tests | Failures | Errors | Skipped | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| `develop` `e912f61ca747c0352cf6544f92579752f0f886e1` | 483 | 0 | 1 | 0 | FAIL |
| `refactor/api-structure` `c59388a7a791425dacdaa0edf2d622f5a3be2919` | 484 | 0 | 145 | 0 | FAIL |

The one error common to both refs was
`S3CompatibleObjectStorageIntegrationTests.writesReadsAndDeletesAPrivateObjectThroughMinio`:
`Missing integration property nexa.object-storage.endpoint`. The test
previously required externally supplied MinIO properties. This was a test
environment dependency, not evidence that the object-storage adapter failed.

The refactor had 144 additional `ApplicationContext` errors. Their common
root was `NoUniqueBeanDefinitionException`: `NotificationUseCase` had two
candidates, `notificationUseCase` and `notificationProjectionPort`. The
projection bean returned the same concrete `NotificationService` as the use
case bean. Earlier initialization of that bean after the structural move
exposed both types to Spring's candidate resolution. These errors were one
startup regression with 144 affected tests, not 144 independent defects.

## Corrections and evidence

- Notification runtime wiring now exposes one `NotificationUseCase` bean and
  one separate `NotificationProjectionPort` delegate. A focused context test
  checks candidate resolution. The existing integration suite exercises
  application startup and notification behavior.
- The S3-compatible object-storage integration test provisions its own
  disposable S3 emulator and private bucket with test-only credentials. CI no
  longer provisions a separate object-storage service for that test. The
  original MinIO image stopped resolving from Docker Hub on a clean CI runner;
  the fixture now uses a pullable LocalStack image.
- A controller test covers remote address and forwarded-header extraction
  through the trusted-proxy resolver, preserving the HTTP adapter seam after
  its lower-level resolver test moved to `edge`.

After these source and test corrections, the same full command passed with
**486 tests, 0 failures, 0 errors, 0 skipped**. `OpenApiContractIT`,
architecture, PostgreSQL migration, security, RLS, concurrency, and
idempotency tests are included in that count. The CI workflow change passed
`actionlint`; remote CI execution was not observed. Java 25 runtime behavior
was not separately tested on this host.

This result closes the observed structural-refactor regression and the shared
object-storage test dependency. It does not certify production deployment, complete
RLS coverage, integrated clients, or Product/System Acceptance.
