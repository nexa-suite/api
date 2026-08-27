package com.nexa.api.shared.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseRuntimeConfigurationValidatorTests {
    @Test
    void acceptsASeparateLoginWithoutRlsBypassOrOwnedObjects() {
        assertThatCode(() -> DatabaseRuntimeConfigurationValidator.validate(
                "nexa_runtime", identity(false, false, false, false, false), 0))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnOwnerEvenWhenTheRoleFlagsLookRestricted() {
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate(
                "nexa_runtime", identity(false, false, false, false, true), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not own the application database");
    }

    @Test
    void rejectsABypassRoleAndApplicationOwnedObjects() {
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate(
                "nexa_runtime", identity(false, true, false, false, false), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not bypass");
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate(
                "nexa_runtime", identity(false, false, false, false, false), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not own application objects");
    }

    @Test
    void rejectsEffectiveUnsafeMembershipsSchemaCreationAndUnapprovedDefiners() {
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate(
                "nexa_runtime", identity(false, false, false, false, false), 0,
                List.of("nexa_privileged"), List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nexa_privileged");
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate(
                "nexa_runtime", identity(false, false, false, false, false), 0,
                List.of(), List.of("sales"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATE objects");
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate(
                "nexa_runtime", identity(false, false, false, false, false), 0,
                List.of(), List.of(), List.of("sales.unsafe_function(integer)")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECURITY DEFINER");
    }

    @Test
    void rejectsDatabaseCreateAndReplicationEvenWhenDirectRlsFlagsAreSafe() {
        Map<String, Object> databaseCreate = new java.util.HashMap<>(identity(false, false, false, false, false));
        databaseCreate.put("database_create", true);
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate("nexa_runtime", databaseCreate, 0))
                .isInstanceOf(IllegalStateException.class);

        Map<String, Object> replication = new java.util.HashMap<>(identity(false, false, false, false, false));
        replication.put("rolreplication", true);
        assertThatThrownBy(() -> DatabaseRuntimeConfigurationValidator.validate("nexa_runtime", replication, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, Object> identity(boolean superuser, boolean bypassRls,
                                                 boolean createDb, boolean createRole, boolean databaseOwner) {
        return Map.of(
                "current_user", "nexa_runtime",
                "session_user", "nexa_runtime",
                "rolcanlogin", true,
                "rolsuper", superuser,
                "rolbypassrls", bypassRls,
                "rolcreatedb", createDb,
                "rolcreaterole", createRole,
                "rolreplication", false,
                "database_create", false,
                "database_owner", databaseOwner);
    }
}
