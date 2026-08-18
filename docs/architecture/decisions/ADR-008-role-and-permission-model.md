# ADR-008: Role and permission model

Status: Accepted

## Context

Nexa needs Platform internal access and Portal Buyer access while preserving one canonical authorization policy across API and clients.

## Decision

Roles belong only to Workspace Membership. Canonical internal roles are `TENANT_ADMIN`, `COMPANY_OWNER`, `SALES`, `WAREHOUSE` and `LOGISTICS`; `BUYER` is the separate Portal role. Surfaces are `PLATFORM` and `PORTAL`; internal roles authenticate only to Platform and Buyer only to Portal. A single role-to-permission policy defines immutable permissions, including `catalog:read`; clients consume session claims but do not become authorization authorities. System-operator activation is an internal system boundary, not a membership role.

## Alternatives considered

- Role on User Account: rejected because one user may hold different roles in multiple workspaces.
- Role maps duplicated in controllers and Angular: rejected because drift can produce authorization gaps.
- Permission tables in this release: deferred until configurable permissions have an approved lifecycle.

## Consequences

Catalog handlers assert `catalog:read` at application level in addition to request security. Future configurable permissions require a policy review and persistence decision.

## Risks

JWT claim drift, missing method security or inconsistent surface checks can grant or deny incorrectly.

## Evidence required

Role/surface/policy unit tests, route authorization tests, API 401/403 tests and Platform/Portal Playwright rejection scenarios.

## Review trigger

Review when permissions become tenant-configurable, roles become custom, or a new application surface is added.
