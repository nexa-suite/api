# ADR-0001: Explicit modular architecture debt

Status: Historical; accepted for FULL-PATCH-1.2. The current API module map is
documented in [bounded-context-module-map.md](../bounded-context-module-map.md).

The decision below preserves the FULL-PATCH-1.2 architecture baseline. Its
module names and open-debt statements describe that historical repository
state, not the current eleven-context API implementation.

IAM, Tenant Management and Catalog Management are closed and expose only named
application contracts. Sales, Warehouse, Logistics, Invoicing and Shared remain
open because their existing read/write surfaces still have cross-context
dependencies that require a dedicated follow-up instead of broad exports.

For each open module, the current temporary surface is its existing named
application port. New dependencies may target only those named ports; direct
imports of another module's infrastructure or domain internals are prohibited.
Closure requires an ArchUnit/Modulith verification with zero illegal accesses,
production application wiring, PostgreSQL integration coverage and a reviewed
public contract. Closure is owned by the corresponding future bounded-context
task; this patch does not broaden those contexts.
