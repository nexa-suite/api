# Contributing to Nexa API

## Welcome

Nexa API is a Spring Boot modular monolith and future business/integration authority. Contributions must preserve bounded-context ownership and domain purity.

## Before contributing

Read the root README, applicable architecture decisions, release notes and [SECURITY.md](./SECURITY.md). Do not add credentials, generated artifacts or undocumented contracts.

## Architecture boundaries

- Keep Presentation, Application, Domain and Infrastructure separate.
- Domain must not import Spring, persistence, JSON or SQL frameworks.
- DTOs belong to Presentation; import records belong to Infrastructure.
- Do not expose persistence entities or domain objects over transport.
- Do not create generic base entities, repositories or unit-of-work abstractions prematurely.
- Do not copy legacy line by line or change contracts without evidence.

## Development workflow

```bash
./mvnw clean test
./mvnw clean package
./mvnw spring-boot:run
```

## Branch strategy

```text
feature/*
    ↓
develop
    ↓
release/vX.Y.Z
    ↓
main
    ↓
annotated tag
    ↓
GitHub Release
    ↓
back-merge to develop
```

## Commit convention

Use `type(scope): description`. Allowed types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`, `perf`, `security`. Avoid messages such as `update files`, `changes`, `misc`, `final version`, `works` or `fix stuff`.

## Testing requirements

Run Maven clean tests and package validation. Domain tests must run without Spring context where practical. Add deterministic tests for every new invariant or mapping rule.

## Documentation requirements

Update README, changelog, release notes or ADRs when scope, commands, architecture or release behavior changes.

## Security requirements

Never add secrets or uncontrolled exception details. Report vulnerabilities through [SECURITY.md](./SECURITY.md), never through public issues.

## Pull request checklist

- [ ] One primary concern per change.
- [ ] No generated artifacts or duplicate Finder copies.
- [ ] No secrets.
- [ ] Tests and package pass.
- [ ] Domain imports remain framework-free.
- [ ] Documentation and ADRs updated.
- [ ] API contract impact reviewed.
- [ ] Tenant and security impact reviewed.

## Release process

Follow [RELEASE_POLICY.md](./RELEASE_POLICY.md). Releases require clean evidence, an annotated tag, a published GitHub Release and a back-merge.
