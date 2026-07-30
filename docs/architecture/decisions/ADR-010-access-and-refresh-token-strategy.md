# ADR-010: Access and refresh token strategy

Status: Accepted

## Context

Platform and Portal need browser authentication with separate surfaces, short-lived API access and durable session revocation without exposing refresh tokens to JavaScript.

## Decision

Issue RS256 JWT access tokens with a 15-minute TTL and configured issuer/audience. Store access tokens only in Angular memory. Store opaque refresh tokens only in HttpOnly, SameSite=Strict cookies named `NEXA_PLATFORM_REFRESH` and `NEXA_PORTAL_REFRESH`; persist SHA-256 hashes. Rotate sessions on refresh, revoke the previous session, and revoke the family on confirmed reuse.

## Alternatives considered

- Access and refresh tokens in localStorage: rejected because XSS would obtain durable credentials.
- Server session state for every API request: rejected because API business endpoints use stateless Bearer validation.
- Long-lived access JWT: rejected because revocation and exposure impact increase with TTL.

## Consequences

Cookie-authenticated refresh and sign-out require strict Origin/CORS controls. RSA key files are local or environment-mounted and never committed. Token validation must include signature, issuer, audience, time claims and expected algorithm.

## Risks

Key rotation and concurrent refresh need explicit tests. Misconfigured Secure cookies break local HTTP if profile policy is wrong.

## Evidence required

JWT validation tests, refresh rotation/reuse tests, browser storage inspection, Origin rejection tests and Playwright session restoration.

## Review trigger

Review when an external identity provider, native mobile authentication, key rotation service or cross-device session management is introduced.
