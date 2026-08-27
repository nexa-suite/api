# Runtime database role

The API runtime must authenticate with a dedicated PostgreSQL login, separate
from the Flyway migrator. When
`NEXA_DATABASE_REQUIRE_LEAST_PRIVILEGE_RUNTIME=true`, startup fails closed if
the datasource is not the configured runtime login, if it is a superuser or
`BYPASSRLS` role, if it can create databases or roles, or if it owns the
application database or application objects.

The local Compose runtime provisions `nexa_runtime` with the restricted role
flags and runs Flyway with the migrator credentials. The same invariant is
provisioned and checked by the security/load workflow. PostgreSQL RLS policy
classification and tenant/workspace scope cleanup remain covered by
`RlsRuntimeDatabaseIsolationIT`; this validator prevents an accidentally
privileged datasource from making those policies ineffective.
