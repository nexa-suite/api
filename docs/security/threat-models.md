# Threat models — Full Patch 1

## Authentication

- Assets: credentials, access tokens, refresh-token families, tenant scope.
- Actors: anonymous user, authenticated user, attacker with stolen token.
- Trust boundaries: browser/API, API/database, API/SMTP.
- Entry points: sign-in, refresh, sign-out, session filter.
- Abuse cases: brute force, token reuse, forged scope, origin abuse.
- Controls/tests: throttle, BCrypt, rotation, family revocation, verified membership filter.
- Residual risk: production secret and TLS configuration require deployment review.

## Password reset

- Assets: reset capability and account credential.
- Entry points: public request and token consume.
- Abuse cases: enumeration, replay, token leakage, concurrent consume.
- Controls/tests: generic response, 256-bit opaque token, SHA-256 hash only, expiry, row lock, rate limit, audit without token.
- Residual risk: SMTP provider availability and abuse monitoring require operational verification.

## Sessions and SSE

- Assets: refresh families and change-feed connection.
- Abuse cases: foreign session revocation, refresh after revocation, stale bearer/SSE access.
- Controls/tests: user-scoped query, ownership update, session revalidation on every bearer request, bounded list.
- Residual risk: long-lived SSE clients must reconnect through their normal unauthorized path.

## Tenant administration

- Assets: memberships, fixed roles, tenant/workspace scope.
- Abuse cases: cross-tenant BOLA, role escalation, removing the final Tenant Admin.
- Controls/tests: role union, explicit policies, scoped assignment table, final-admin concurrency rule.
- Residual risk: future administrative capabilities remain deferred to FULL-PATCH-2.

## Organization onboarding

- Assets: registration data, generated tenant/workspace IDs, founder identity.
- Trust boundary: anonymous browser to API; system operator to activation endpoint.
- Abuse cases: slug collision, public self-activation, duplicate activation, invalid plan/terms.
- Controls/tests: server-generated IDs, unique slug, pending state, row lock, operator-only activation, audit.
- Residual risk: operator UI is intentionally deferred.

## Buyer Portal isolation

- Assets: Buyer membership and Client Account data.
- Abuse cases: cross-client, cross-workspace and internal-role access.
- Controls/tests: Buyer-specific membership path, object scope and role/surface policy.
- Residual risk: additional Client Account features are deferred.

## Change Feed/SSE

- Assets: scoped operational events and connection capacity.
- Abuse cases: unscoped event stream, revoked session continuing to receive events, connection exhaustion.
- Controls/tests: verified access context, audience filtering, bounded registry and session revalidation.
- Residual risk: distributed connection capacity is outside this modular-monolith gate.
