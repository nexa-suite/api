# Optional runtime tiers

Nexa currently implements these tiers:

- Client Tier: Angular applications for Platform and Buyer Portal.
- Server/Application Tier: Spring Boot REST API with domain and application boundaries.
- Data Tier: PostgreSQL, JPA and Flyway migrations.

The following tiers remain optional. No tier below is active in TASK-NEXA-005.

| Tier | Problem it would solve | Evidence required | Consistency implications | Security implications | Operational cost | Activation trigger |
| --- | --- | --- | --- | --- | --- | --- |
| Edge and Delivery | TLS termination, routing and static delivery at scale | Measured traffic, latency and availability needs | Routing must preserve tenant and workspace headers | Certificate, WAF and origin policy ownership | Additional ingress and incident surface | Current delivery limits measured |
| Cache | Repeated expensive reads | Repeated reads, target latency and hit-rate baseline | Invalidation and stale-data tolerance must be explicit | Tenant keys and eviction isolation | Cache operation, memory and invalidation | All Cache Tier rule conditions met |
| Messaging and Integration | Reliable asynchronous workflows and external integration | Volume, retry and delivery measurements | Idempotency, ordering and replay contracts | Credentials, payload minimization and audit | Broker, consumers and replay operations | Synchronous boundary is measured bottleneck |
| Object Storage | Durable large files and generated documents | File volume, retention and download measurements | Metadata and object lifecycle must remain consistent | Signed URLs, encryption and tenant prefixes | Storage, lifecycle and egress | Database storage is proven unsuitable |
| Observability | Correlated diagnostics and service-level evidence | Incident patterns and missing signals | Event timestamps and correlation IDs must be trustworthy | Avoid secrets and personal data in telemetry | Retention, dashboards and alert ownership | Current logs cannot answer operational questions |
| Search | Fast discovery across large catalog and documents | Query latency, corpus size and relevance baseline | Index freshness and rebuild policy must be explicit | Tenant filters enforced in every query | Index lifecycle and synchronization | Database search is measured insufficient |

## Cache Tier rule

Do not add Redis, cache interfaces or cache annotations until all conditions hold:

- repeated expensive reads are measured;
- target latency is documented;
- cache ownership is known;
- invalidation rules are defined;
- stale-data tolerance is defined;
- metrics prove the benefit.

Purchase Request writes must never depend on cache correctness.
