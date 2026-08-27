package com.nexa.api.shared.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
                   d.datdba = r.oid as database_owner
              from pg_roles r
              join pg_database d on d.datname = current_database()
             where r.rolname = current_user
            """;
    private static final String OWNED_OBJECTS_SQL = """
            select count(*)
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where c.relowner = current_user::regrole
               and c.relkind in ('r', 'p', 'v', 'm', 'f')
               and n.nspname not in ('pg_catalog', 'information_schema')
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
            validate(configuredRuntimeUser, identity, ownedObjects == null ? 0L : ownedObjects);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            fail("runtime database identity could not be inspected");
        }
    }

    static void validate(String configuredRuntimeUser, Map<String, Object> identity, long ownedObjects) {
        String currentUser = text(identity.get("current_user"));
        String sessionUser = text(identity.get("session_user"));
        if (!configuredRuntimeUser.equals(currentUser) || !currentUser.equals(sessionUser)) {
            fail("datasource did not authenticate as the configured runtime role");
        }
        if (!flag(identity, "rolcanlogin") || flag(identity, "rolsuper") || flag(identity, "rolbypassrls")
                || flag(identity, "rolcreatedb") || flag(identity, "rolcreaterole")) {
            fail("runtime role must be login-capable and must not bypass database isolation privileges");
        }
        if (flag(identity, "database_owner")) fail("runtime role must not own the application database");
        if (ownedObjects > 0) fail("runtime role must not own application objects");
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
