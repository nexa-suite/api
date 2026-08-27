package com.nexa.api.shared.infrastructure.security;

import org.junit.jupiter.api.Test;

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
                "database_owner", databaseOwner);
    }
}
