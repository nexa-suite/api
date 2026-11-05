package com.nexa.api.bootstrap.runtime.database;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ModernUpInternalOperatorIncomingMembershipGuardIT {
    private static final String UNSAFE_MEMBER_ROLE = "modern_up_unsafe_operator_login_member";
    private static final Path MODERN_UP_SCRIPT = Path.of("ops/compose/scripts/modern-up.sh");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername("nexa")
            .withPassword("test-only-admin-password");

    @Test
    void modernUpRejectsRolesGrantedIntoTheInternalOperatorLogin() throws Exception {
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE nexa_internal_operator NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_internal_operator_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT nexa_internal_operator TO nexa_internal_operator_login WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
            statement.execute("CREATE ROLE " + UNSAFE_MEMBER_ROLE + " NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        }

        boolean unsafeMembershipCreated = false;
        try {
            try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("GRANT nexa_internal_operator_login TO " + UNSAFE_MEMBER_ROLE
                        + " WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
                unsafeMembershipCreated = true;
            }

            assertThatThrownBy(ModernUpInternalOperatorIncomingMembershipGuardIT::runOperatorLoginGuard)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Existing internal-operator login has unsafe attributes, memberships, or ownership");

            try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("REVOKE nexa_internal_operator_login FROM " + UNSAFE_MEMBER_ROLE);
                statement.execute("DROP ROLE " + UNSAFE_MEMBER_ROLE);
                unsafeMembershipCreated = false;
            }

            assertThatCode(ModernUpInternalOperatorIncomingMembershipGuardIT::runOperatorLoginGuard)
                    .as("the intended capability-role membership remains valid")
                    .doesNotThrowAnyException();
        } finally {
            if (unsafeMembershipCreated) {
                try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                    statement.execute("REVOKE nexa_internal_operator_login FROM " + UNSAFE_MEMBER_ROLE);
                    statement.execute("DROP ROLE " + UNSAFE_MEMBER_ROLE);
                }
            }
        }
    }

    private static void runOperatorLoginGuard() throws Exception {
        String guard = operatorLoginGuard();
        String sql = """
                DO $operator_guard$
                DECLARE
                    internal_operator_role pg_roles%ROWTYPE;
                    internal_operator_login pg_roles%ROWTYPE;
                BEGIN
                    SELECT * INTO internal_operator_role FROM pg_roles WHERE rolname = 'nexa_internal_operator';
                    SELECT * INTO internal_operator_login FROM pg_roles WHERE rolname = 'nexa_internal_operator_login';
                """ + guard + """
                END;
                $operator_guard$;
                """;
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "nexa", "test-only-admin-password");
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String operatorLoginGuard() throws Exception {
        String script = Files.readString(MODERN_UP_SCRIPT);
        String startMarker = "\t\tIF NOT internal_operator_login.rolcanlogin";
        String endMarker = "\n\t\tEXECUTE format('ALTER ROLE nexa_internal_operator_login";
        int start = script.indexOf(startMarker);
        int end = script.indexOf(endMarker, start);
        assertThat(start).as("modern-up internal-operator login attribute guard start").isGreaterThanOrEqualTo(0);
        assertThat(end).as("modern-up internal-operator login attribute guard end").isGreaterThan(start);
        return script.substring(start, end);
    }
}
