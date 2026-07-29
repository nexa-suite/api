# Authentication and session application contract

## Use cases

- `SignInUseCase` verifies a login identifier and password through `UserAccountQueryPort` and `PasswordVerificationPort`, resolves the surface policy, issues tokens through `AuthenticationTokenPort` and starts a session through `SessionPort`.
- `RefreshSessionUseCase` looks up the presented refresh token, checks session time using `Clock`, issues replacement tokens and atomically rotates the token. Reuse raises `RefreshTokenReuseDetectedException` after family revocation.
- `SignOutUseCase` revokes the session identified by the access token. Missing access tokens are a no-op so logout is idempotent.
- `CurrentSessionUseCase` returns the active session projection without exposing access or refresh token material.

## Security boundaries

The application layer is token-format agnostic. JWT signing, opaque-token generation, token hashing and persistence belong to adapters that implement the ports. The `BCryptPasswordVerifier` is the only credential adapter in this task and safely returns `false` for malformed encoded hashes.

Refresh-token storage must make `SessionPort.rotateRefreshToken(...)` atomic and retain enough token-family metadata to distinguish a consumed token from an unknown token. It must not store clear refresh tokens in persistent storage; the presented value should be matched through an adapter-owned hash or equivalent protected lookup. Reuse is a family-level security event, not merely a failed login.

This task does not change the existing shared Spring Security filter chain or expose authentication routes. Runtime wiring, REST DTOs, JWT policy, tenant/workspace membership checks and database transactions require their own approved contract and validation.

## Consistency boundary

The rotate operation is the single state-changing consistency boundary: consume the current refresh token and persist its replacement together. The adapter must protect concurrent refresh requests so only one request can rotate a token. Family revocation must be durable before the reuse error is returned.
