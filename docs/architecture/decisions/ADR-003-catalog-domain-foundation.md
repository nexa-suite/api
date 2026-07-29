# ADR-003: Catalog Domain Foundation

Status: Accepted

## Context

The canonical 50-item seed is already available. The legacy model mixes commercial catalog definition with stock ownership, tenant assignment, commands, base entities and persistence concerns. Future clients require a pure domain boundary that can survive multiple transport technologies.

## Decision

- Model `CatalogItem` as the Catalog Management aggregate.
- Keep stock, lots, reservations and movements in Warehouse.
- Model Brand and Category as value concepts until independent lifecycle evidence exists.
- Do not create a base entity, generic repository or unit of work.
- Translate the seed through an Infrastructure anticorruption layer.
- Keep transport DTOs and future contracts outside Domain.

This task is the explicit approval of this decision.

## Alternatives considered

- Copy the ASP.NET model line by line.
- Move all concepts into Shared.
- Create Brand and Category aggregates immediately.
- Create persistence mappings now.
- Expose the seed directly over HTTP.

These alternatives were rejected because they preserve incorrect ownership, create premature lifecycle or persistence commitments, or leak import and transport concerns into Domain.

## Consequences

Positive:

- Stock ownership is explicit.
- Domain tests run without Spring context.
- Seed import can evolve independently from aggregate shape.
- Angular, Kotlin, Swift and automation clients can consume future contracts without sharing Java domain classes.

Negative:

- Future persistence and transport adapters need explicit mapping.
- Tenant ownership remains unresolved until IAM and Tenant Management are designed.
- Brand and Category lifecycle work remains future scope.

## Review trigger

Review this ADR when editable catalog use cases exist, Brand or Category require independent lifecycle, persistence is designed, or tenant ownership is defined.
