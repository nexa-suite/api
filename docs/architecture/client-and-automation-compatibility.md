# Client and Automation Compatibility

## Purpose

This document defines constraints for future Angular, Kotlin, Swift and automation clients. It establishes boundaries; it does not add functionality or SDKs.

## Client neutrality

- API domain code does not depend on Angular, Kotlin or Swift.
- Domain entities are not serialized directly.
- Future DTOs belong to Presentation.
- Future contracts will be documented through OpenAPI.
- Each client maps contracts to its own models.
- IDs, dates, money and enums use explicit representations.

## Mobile readiness

- Kotlin and Swift clients will consume the same HTTP contracts.
- Backend Java code is not shared as client domain code.
- Persistence implementation details are not exposed.
- Internal class names are not contract names.
- Monetary amounts never use floating-point representations.
- Future timestamps include timezone information.
- Errors follow Problem Details.
- API versioning is explicit.

## Automation and AI readiness

- Future agents enter through Application use cases.
- No agent accesses tables directly.
- No agent bypasses authorization.
- Automated actions are auditable.
- Automated commands carry actor, tenant, correlation ID and purpose when those concepts are defined.
- AI integrations are Infrastructure adapters.
- Domain code does not depend on AI SDKs.
- No AI SDK, chatbot or agent is added in this task.
