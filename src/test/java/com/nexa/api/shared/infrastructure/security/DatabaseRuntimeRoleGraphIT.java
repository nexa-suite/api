package com.nexa.api.shared.infrastructure.security;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.nexa.api.support.PostgresIntegrationSupport.openMigratorConnection;
import static com.nexa.api.support.PostgresIntegrationSupport.openRuntimeConnection;

/** Runs the runtime-role validator against real PostgreSQL membership semantics. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class DatabaseRuntimeRoleGraphIT extends PostgresIntegrationSupport {

    @Test
    void acceptsAHarmlessInheritedMembership() throws Exception {
        RoleFixture fixture = new RoleFixture();
        try {
            String harmless = fixture.createRole(false);
            fixture.grant(harmless, "nexa_runtime", "with inherit true, set true");

            assertThat(hasRolePrivilege(harmless, "USAGE")).isTrue();
            assertThatCode(this::newRuntimeValidator).doesNotThrowAnyException();
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsAnInheritedBypassRlsRole() throws Exception {
        RoleFixture fixture = new RoleFixture();
        try {
            String privileged = fixture.createRole(true);
            fixture.grant(privileged, "nexa_runtime", "with inherit true, set false");

            assertThat(hasRolePrivilege(privileged, "USAGE")).isTrue();
            assertThatThrownBy(this::newRuntimeValidator)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(privileged);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsASetRolePathToABypassRlsRoleEvenWithoutInheritance() throws Exception {
        RoleFixture fixture = new RoleFixture();
        try {
            String privileged = fixture.createRole(true);
            fixture.grant(privileged, "nexa_runtime", "with inherit false, set true");

            assertThat(hasRolePrivilege(privileged, "USAGE")).isFalse();
            assertThat(hasRolePrivilege(privileged, "SET")).isTrue();
            assertThat(canSetRole(privileged)).isTrue();
            assertThatThrownBy(this::newRuntimeValidator)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(privileged);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsATransitivePrivilegedRoleReachableThroughSafeMemberships() throws Exception {
        RoleFixture fixture = new RoleFixture();
        try {
            String intermediate = fixture.createRole(false);
            String privileged = fixture.createRole(true);
            fixture.grant(privileged, intermediate, "with inherit true, set true");
            fixture.grant(intermediate, "nexa_runtime", "with inherit true, set true");

            assertThat(hasRolePrivilege(privileged, "USAGE")).isTrue();
            assertThat(hasRolePrivilege(privileged, "SET")).isTrue();
            assertThat(canSetRole(privileged)).isTrue();
            assertThatThrownBy(this::newRuntimeValidator)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(privileged);
        } finally {
            fixture.close();
        }
    }

    @Test
    void rejectsAnExecutableUnapprovedSecurityDefiner() throws Exception {
        String function = "v0161_definer_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = openMigratorConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create function integration." + function
                    + "() returns integer language sql security definer as $$ select 1 $$");
            statement.execute("grant execute on function integration." + function + "() to nexa_runtime");
        }
        try {
            assertThatThrownBy(this::newRuntimeValidator)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("integration." + function + "()");
        } finally {
            try (Connection connection = openMigratorConnection(); Statement statement = connection.createStatement()) {
                statement.execute("revoke execute on function integration." + function + "() from nexa_runtime");
                statement.execute("drop function integration." + function + "()");
            }
        }
    }

    private DatabaseRuntimeConfigurationValidator newRuntimeValidator() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                runtimeJdbcUrl(), runtimeDatabaseUsername(), runtimeDatabasePassword());
        return new DatabaseRuntimeConfigurationValidator(new JdbcTemplate(dataSource),
                new MockEnvironment().withProperty("NEXA_DATABASE_RUNTIME_USERNAME", runtimeDatabaseUsername()));
    }

    private boolean hasRolePrivilege(String role, String privilege) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                runtimeJdbcUrl(), runtimeDatabaseUsername(), runtimeDatabasePassword());
        Boolean result = new JdbcTemplate(dataSource).queryForObject(
                "select pg_has_role(current_user, ?::name, ?)", Boolean.class, role, privilege);
        return Boolean.TRUE.equals(result);
    }

    private boolean canSetRole(String role) throws SQLException {
        try (Connection connection = openRuntimeConnection(); Statement statement = connection.createStatement()) {
            statement.execute("set role " + role);
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private static final class RoleFixture {
        private final List<String> roles = new ArrayList<>();
        private final List<Grant> grants = new ArrayList<>();
        private final String suffix = UUID.randomUUID().toString().replace("-", "");

        private String createRole(boolean bypassRls) throws SQLException {
            String role = "v0161_" + suffix + "_" + roles.size();
            try (Connection connection = openMigratorConnection(); Statement statement = connection.createStatement()) {
                statement.execute("create role " + role + " nologin nosuperuser nocreatedb nocreaterole noreplication "
                        + (bypassRls ? "bypassrls" : "nobypassrls"));
            }
            roles.add(role);
            return role;
        }

        private void grant(String role, String member, String options) throws SQLException {
            try (Connection connection = openMigratorConnection(); Statement statement = connection.createStatement()) {
                statement.execute("grant " + role + " to " + member + " " + options);
            }
            grants.add(new Grant(role, member));
        }

        private void close() throws SQLException {
            try (Connection connection = openMigratorConnection(); Statement statement = connection.createStatement()) {
                for (int index = grants.size() - 1; index >= 0; index--) {
                    Grant grant = grants.get(index);
                    statement.execute("revoke " + grant.role() + " from " + grant.member());
                }
                for (int index = roles.size() - 1; index >= 0; index--) {
                    statement.execute("drop role " + roles.get(index));
                }
            }
        }

        private record Grant(String role, String member) { }
    }
}
