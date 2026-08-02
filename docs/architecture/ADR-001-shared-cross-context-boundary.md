# ADR-001: Shared cross-context boundary

Status: temporary

`shared` remains open because the current HTTP edge composes authentication, tenant-context resolution, change-feed streaming and the single RFC 9457 error surface across IAM, Tenant Management, Sales, Warehouse and Logistics. Those concerns are adapters at the application edge; they do not own domain state or inbound business ports.

Forbidden accesses:

- no `shared` domain model may import a bounded-context module;
- no `shared` class may implement an inbound application port from another module;
- no bounded context may import `shared` infrastructure persistence or business aggregates;
- cross-context types are exposed only through named interfaces.

Closure target: move the HTTP/security composition to a dedicated `edge` module with explicit inbound ports, leave `shared` with framework-neutral error primitives and close `shared` before the next bounded-context release. This ADR is the only temporary exception for the core-module closure gate.
