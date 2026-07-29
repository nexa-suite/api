# IAM domain foundation

## Scope

IAM owns the identity account and the lifecycle of an authentication session. This task establishes framework-free domain objects and application ports; it does not add REST endpoints, persistence, migrations, JWT handling, tenant membership or workspace state.

## UserAccount

`UserAccount` contains only stable identity data: `UserAccountId`, `Username`, `EmailAddress`, `DisplayName` and `UserAccountStatus`. It starts `ACTIVE` and exposes explicit `activate`, `suspend` and `disable` behavior. Suspended and disabled accounts cannot authenticate.

Credentials, roles, permissions, client surface, tenant membership and workspace assignment are deliberately outside the aggregate. A password hash is carried only by the application-side `StoredUserAccount` projection used by the authentication port.

## AuthenticationSession

`AuthenticationSession` records the account, `ClientSurface` (`PLATFORM` or `PORTAL`), refresh-token family, creation/expiration instants and revocation status. It uses no Spring, JPA, JWT or transport types. Expiration is evaluated against an explicit instant supplied by the application `Clock`.

Refresh tokens are rotated through an application port with an atomic consume-and-replace contract. A previously consumed token must be reported as reuse; the application then revokes every session in that refresh-token family.

## Policy boundary

`AccessPolicyPort` supplies the role and permissions for an account and surface. IAM does not normalize or own the tenant policy vocabulary in this foundation, so a future Tenant Management adapter can provide canonical policy data without a circular dependency or changes to tenant paths.
