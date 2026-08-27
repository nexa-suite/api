package com.nexa.api.shared.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Fails startup when the application datasource can bypass the database isolation boundary. */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "nexa.database.require-least-privilege-runtime", havingValue = "true")
public final class DatabaseRuntimeConfigurationValidator {
    private static final String IDENTITY_SQL = """
            select current_user as current_user,
                   session_user as session_user,
                   r.rolcanlogin,
                   r.rolsuper,
                   r.rolbypassrls,
                   r.rolcreatedb,
                   r.rolcreaterole,
                   r.rolreplication,
                   has_database_privilege(current_user, current_database(), 'CREATE') as database_create,
                   d.datdba = r.oid as database_owner
              from pg_roles r
              join pg_database d on d.datname = current_database()
             where r.rolname = current_user
            """;
    private static final String OWNED_OBJECTS_SQL = """
            select count(*)
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where c.relkind in ('r', 'p', 'v', 'm', 'f', 'S')
               and n.nspname not in ('pg_catalog', 'information_schema')
               and (pg_has_role(current_user, c.relowner, 'USAGE')
                    or pg_has_role(current_user, c.relowner, 'SET'))
            """;
    private static final String UNSAFE_ROLES_SQL = """
            select r.rolname
              from pg_roles r
             where r.oid <> current_user::regrole
               and (r.rolsuper or r.rolbypassrls or r.rolcreatedb or r.rolcreaterole or r.rolreplication)
               and (pg_has_role(current_user, r.oid, 'USAGE')
                    or pg_has_role(current_user, r.oid, 'SET'))
             order by r.rolname
            """;
    private static final String SCHEMA_CREATE_SQL = """
            select n.nspname
              from pg_namespace n
             where n.nspname not like 'pg_%'
               and n.nspname <> 'information_schema'
               and has_schema_privilege(current_user, n.oid, 'CREATE')
             order by n.nspname
            """;
    private static final String UNSAFE_SECURITY_DEFINER_SQL = """
            select n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
              from pg_proc p
              join pg_namespace n on n.oid = p.pronamespace
             where p.prosecdef
               and n.nspname not in ('pg_catalog', 'information_schema')
               and has_function_privilege(current_user, p.oid, 'EXECUTE')
               and p.oid is distinct from to_regprocedure('integration.purge_expired_change_events(integer)')
             order by 1
            """;
    private static final String UNSAFE_ALLOWED_SECURITY_DEFINER_SQL = """
            select count(*)
              from pg_proc p
             where p.oid = to_regprocedure('integration.purge_expired_change_events(integer)')
               and (p.proowner = current_user::regrole
                    or not exists (
                        select 1
                          from unnest(coalesce(p.proconfig, array[]::text[])) setting
                         where setting like 'search_path=pg_catalog%'
                    ))
            """;

    public DatabaseRuntimeConfigurationValidator(JdbcTemplate jdbc, Environment environment) {
        String configuredRuntimeUser = firstText(
                environment.getProperty("NEXA_DATABASE_RUNTIME_USERNAME"),
                environment.getProperty("spring.datasource.username"));
        if (configuredRuntimeUser == null) fail("runtime database username is required");

        Map<String, Object> identity;
        try {
            identity = jdbc.queryForMap(IDENTITY_SQL);
            Long ownedObjects = jdbc.queryForObject(OWNED_OBJECTS_SQL, Long.class);
            List<String> unsafeRoles = jdbc.query(UNSAFE_ROLES_SQL, (result, row) -> result.getString(1));
            List<String> creatableSchemas = jdbc.query(SCHEMA_CREATE_SQL, (result, row) -> result.getString(1));
            List<String> unsafeSecurityDefiners = jdbc.query(UNSAFE_SECURITY_DEFINER_SQL, (result, row) -> result.getString(1));
            Long unsafeAllowedSecurityDefiners = jdbc.queryForObject(UNSAFE_ALLOWED_SECURITY_DEFINER_SQL, Long.class);
            if (unsafeAllowedSecurityDefiners != null && unsafeAllowedSecurityDefiners > 0) {
                unsafeSecurityDefiners = new java.util.ArrayList<>(unsafeSecurityDefiners);
                unsafeSecurityDefiners.add("integration.purge_expired_change_events(integer)");
            }
            validate(configuredRuntimeUser, identity, ownedObjects == null ? 0L : ownedObjects,
                    unsafeRoles, creatableSchemas, unsafeSecurityDefiners);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            fail("runtime database identity could not be inspected");
        }
    }

    static void validate(String configuredRuntimeUser, Map<String, Object> identity, long ownedObjects) {
        validate(configuredRuntimeUser, identity, ownedObjects, List.of(), List.of(), List.of());
    }

    static void validate(String configuredRuntimeUser, Map<String, Object> identity, long ownedObjects,
                         List<String> unsafeRoles, List<String> creatableSchemas,
                         List<String> unsafeSecurityDefiners) {
        String currentUser = text(identity.get("current_user"));
        String sessionUser = text(identity.get("session_user"));
        if (!configuredRuntimeUser.equals(currentUser) || !currentUser.equals(sessionUser)) {
            fail("datasource did not authenticate as the configured runtime role");
        }
        if (!flag(identity, "rolcanlogin") || flag(identity, "rolsuper") || flag(identity, "rolbypassrls")
                || flag(identity, "rolcreatedb") || flag(identity, "rolcreaterole")
                || flag(identity, "rolreplication") || flag(identity, "database_create")) {
            fail("runtime role must be login-capable and must not bypass database isolation privileges");
        }
        if (flag(identity, "database_owner")) fail("runtime role must not own the application database");
        if (ownedObjects > 0) fail("runtime role must not own application objects");
        if (!unsafeRoles.isEmpty()) fail("runtime role can inherit or SET ROLE into unsafe roles: " + String.join(", ", unsafeRoles));
        if (!creatableSchemas.isEmpty()) fail("runtime role must not CREATE objects in application schemas: " + String.join(", ", creatableSchemas));
        if (!unsafeSecurityDefiners.isEmpty()) fail("runtime role has unapproved SECURITY DEFINER execution: " + String.join(", ", unsafeSecurityDefiners));
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank() && !value.contains("${")) return value;
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean flag(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private static void fail(String message) {
        throw new IllegalStateException("Invalid database runtime configuration: " + message);
    }
}
