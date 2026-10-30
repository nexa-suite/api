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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WorkspaceScopeReaderIncomingMembershipGuardIT {
    private static final String UNSAFE_MEMBER_ROLE = "v175_unsafe_reader_login_member";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine")
            .withDatabaseName("nexa")
            .withUsername("nexa")
            .withPassword("test-only-admin-password");

    @Test
    void v175PreparationRejectsRolesGrantedIntoTheReaderLogin() throws Exception {
        try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.flyway_schema_history (version varchar(50), success boolean NOT NULL)");
            statement.execute("INSERT INTO public.flyway_schema_history(version, success) VALUES ('174', true)");
            statement.execute("CREATE ROLE nexa_migrator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_workspace_scope_reader NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("CREATE ROLE nexa_workspace_scope_reader_login LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
            statement.execute("GRANT nexa_workspace_scope_reader TO nexa_workspace_scope_reader_login WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
        }

        boolean unsafeRoleCreated = false;
        try {
            try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE " + UNSAFE_MEMBER_ROLE + " NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
                unsafeRoleCreated = true;
                statement.execute("GRANT nexa_workspace_scope_reader_login TO " + UNSAFE_MEMBER_ROLE
                        + " WITH ADMIN FALSE, INHERIT FALSE, SET TRUE");
            }

            assertThatThrownBy(() -> runPreparation(Path.of("ops/database/prepare-v175-workspace-scope-reader.sql")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("V175 requires one isolated NOLOGIN privilege role and one NOINHERIT login with only SET ROLE membership");
        } finally {
            if (unsafeRoleCreated) {
                try (Connection connection = POSTGRES.createConnection(""); Statement statement = connection.createStatement()) {
                    statement.execute("REVOKE nexa_workspace_scope_reader_login FROM " + UNSAFE_MEMBER_ROLE);
                    statement.execute("DROP ROLE " + UNSAFE_MEMBER_ROLE);
                }
            }
        }
    }

    private static void runPreparation(Path path) throws Exception {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(Files.readString(path));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
}
