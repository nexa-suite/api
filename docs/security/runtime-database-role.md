# Runtime database role

The API runtime must authenticate with a dedicated PostgreSQL login, separate
from the Flyway migrator. When
`NEXA_DATABASE_REQUIRE_LEAST_PRIVILEGE_RUNTIME=true`, startup fails closed if
the datasource is not the configured runtime login, if it is a superuser or
`BYPASSRLS` role, if it can create databases or roles, has replication
privileges, or if
it owns the application database or application objects. The validator also
uses PostgreSQL effective privilege checks to reject unsafe inherited or
`SET ROLE`-reachable memberships, application-schema `CREATE`, and executable
unapproved `SECURITY DEFINER` functions. The one current maintenance function
allowlist is `integration.purge_expired_change_events(integer)` and is accepted
only when the runtime cannot own it and its fixed `search_path` begins with
`pg_catalog`.

The local Compose runtime provisions `nexa_runtime` with the restricted role
flags and runs Flyway with the migrator credentials. The same invariant is
provisioned and checked by the security/load workflow. PostgreSQL RLS policy
classification and tenant/workspace scope cleanup remain covered by
`RlsRuntimeDatabaseIsolationIT`; this validator prevents an accidentally
privileged datasource from making those policies ineffective. Real PostgreSQL
integration coverage exercises harmless membership, inherited `BYPASSRLS`,
explicit `SET ROLE`, and transitive privileged-role paths. The validator does
not claim that every tenant table is protected by RLS; the AS-IS scope registry
and its deliberate exceptions remain authoritative.
