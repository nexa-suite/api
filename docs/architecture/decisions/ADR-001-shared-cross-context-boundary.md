# ADR-001: Shared cross-context boundary

Status: Fulfilled (2026-09-24).

Historical context at acceptance: `shared` remained open because the HTTP edge
composed authentication, tenant-context resolution, change-feed streaming and
the single RFC 9457 error surface across IAM, Tenant Management, Sales,
Warehouse and Logistics. Those concerns were adapters at the application edge;
they did not own domain state or inbound business ports.

Forbidden accesses:

- no `shared` domain model may import a bounded-context module;
- no `shared` class may implement an inbound application port from another module;
- no bounded context may import `shared` infrastructure persistence or business aggregates;
- cross-context types are exposed only through named interfaces.

Closure target: move HTTP/security composition to a dedicated `edge` module,
leave `shared` with framework-neutral primitives and technical contracts, and
close `shared` before the next bounded-context release. This ADR was the only
temporary exception for the core-module closure gate.

Closure evidence: `edge` now owns HTTP, security, Problem Details and streaming
composition. `shared` is a closed Modulith module containing only
framework-neutral contracts and primitives. Architecture verification checks
the module set and shared dependency closure.
