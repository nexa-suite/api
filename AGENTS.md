# Repository Working Agreement

## Authority

- Use accepted Nexa Product, Domain and architecture decisions as the semantic
  authority.
- Do not invent Product meaning, silently close OPEN decisions, or derive
  Bounded Context ownership from a package, module, schema, endpoint or
  repository name.
- Read `README.md`, `.github/CONTRIBUTING.md`, `.github/SECURITY.md` and the
  relevant accepted architecture documentation before changing behavior.

## Repository state

- Inspect the actual branch, worktree, remote metadata and working tree before
  editing.
- Fetch remote metadata before creating new work when permitted; do not merge
  fetched changes into a user's working branch.
- Preserve unrelated local work. Use an isolated worktree when the checkout is
  dirty.

## Engineering boundaries

- The server remains authoritative for business decisions, tenant and
  workspace isolation, authorization and durable state.
- This repository is a modular monolith; implementation modules are not
  automatically DDD Bounded Contexts. Do not introduce microservices merely
  because strategic Bounded Contexts exist.
- Keep Presentation, Application, Domain and Infrastructure boundaries intact.
  Domain code must remain independent of Spring, persistence, JSON and SQL
  frameworks where the existing architecture requires it.
- Do not expose persistence entities or domain objects directly over transport.
- Client-supplied tenant or workspace identifiers are not authority.
- Authorization remains server-side; RLS is defense in depth, not a substitute
  for application authorization.
- Transaction boundaries, optimistic/CAS or locking choices, deterministic
  lock ordering, concurrency outcomes and idempotency must match the actual
  invariant. Do not claim exactly-once behavior without durable evidence.
- Authoritative state and outbox facts must respect their atomic transaction
  boundary. Asynchronous delivery does not change source-of-truth ownership.
- API contract changes require compatibility analysis. Migrations require
  deliberate review, and immutable business history must not be rewritten.

## Evidence and security

- Claim only tests, metrics, compatibility, acceptance and release readiness
  that were actually verified.
- Do not weaken authorization, tenant isolation, RLS, secret handling, PII
  handling, data integrity, concurrency guards, idempotency or CI gates to make
  a change pass.
- Do not add credentials, generated artifacts or undocumented contracts.

## SCM and artifacts

- Follow `.github/CONTRIBUTING.md` for branch, commit, review and release flow.
- Use Conventional Commits and preserve real authorship and signatures.
- Do not force-push, rewrite shared history, create fake commits, invent
  contributors, merge automatically, create releases or create tags for this
  governance change.
- Repository-facing artifacts must be neutral, professional and free of
  internal orchestration residue, temporary placeholders and AI attribution.

## Validation and handoff

- Review the task diff, run `git diff --check`, and use the narrowest relevant
  repository checks for documentation-only changes.
- Do not claim a build, test, security review, CI result or deployment that was
  not executed and observed.
- End the task with factual result, changes, validation, commit, risk, open
  decision and unverified-item information.
