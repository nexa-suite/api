package com.nexa.api.bootstrap.runtime.database.tenant;

import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantBusinessDatabaseReadinessVerifierTests {
    @Test
    void onlyExactNeonAdministrativeCreatorMembershipIsAllowed() {
        for (String role : new String[] {"nexa_runtime", "nexa_migrator", "nexa_policy_snapshot_writer"}) {
            assertThatCode(() -> TenantBusinessDatabaseReadinessVerifier.verifyNeonCreatorMembership(
                    role, "neondb_owner", "cloud_admin", true, false, false)).doesNotThrowAnyException();
            for (boolean[] flags : new boolean[][] {{false,false,false}, {true,true,false}, {true,false,true}}) {
                assertThatThrownBy(() -> TenantBusinessDatabaseReadinessVerifier.verifyNeonCreatorMembership(
                        role, "neondb_owner", "cloud_admin", flags[0], flags[1], flags[2]))
                        .isInstanceOf(IllegalStateException.class);
            }
            assertThatThrownBy(() -> TenantBusinessDatabaseReadinessVerifier.verifyNeonCreatorMembership(
                    role, "unexpected-owner", "cloud_admin", true, false, false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> TenantBusinessDatabaseReadinessVerifier.verifyNeonCreatorMembership(
                    role, "neondb_owner", "unexpected-grantor", true, false, false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> TenantBusinessDatabaseReadinessVerifier.verifyNeonCreatorMembership(
                    "powerful-parent", role, "cloud_admin", true, false, false)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void sharedIdentityVerifierRejectsWrongPhysicalIdentityAndWorkspace() throws Exception {
        UUID tenant = UUID.randomUUID(), database = UUID.randomUUID(), workspace = UUID.randomUUID();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet identity = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(identity);
        when(identity.next()).thenReturn(true, false);
        when(identity.getObject("tenant_id", UUID.class)).thenReturn(tenant);
        when(identity.getObject("database_identity", UUID.class)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> TenantBusinessDatabaseReadinessVerifier.verifyIdentityAndWorkspace(
                connection, tenant, workspace, database)).isInstanceOf(IllegalStateException.class);
        when(identity.next()).thenReturn(true, false);
        when(identity.getObject("database_identity", UUID.class)).thenReturn(database);
        PreparedStatement scoped = mock(PreparedStatement.class);
        ResultSet anchors = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(scoped);
        when(scoped.executeQuery()).thenReturn(anchors);
        when(anchors.next()).thenReturn(false);
        assertThatThrownBy(() -> TenantBusinessDatabaseReadinessVerifier.verifyIdentityAndWorkspace(
                connection, tenant, workspace, database)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workspace scope anchor is missing");
    }

    @Test
    void localRuntimeStillRejectsMissingBootstrapOwnerAndAnyMembership() throws Exception {
        for (int rejectedColumn : new int[] {22,23,24,25,26}) {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet result = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(result);
            when(result.next()).thenReturn(true);
            when(result.getString(1)).thenReturn("nexa_runtime");
            for (int trueColumn : new int[] {2,8,16,18,20,22}) when(result.getBoolean(trueColumn)).thenReturn(true);
            when(result.getBoolean(rejectedColumn)).thenReturn(rejectedColumn != 22);
            assertThatThrownBy(() -> TenantBusinessDatabaseReadinessVerifier.verifyRuntimeRole(connection))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not least-privileged");
        }
    }
}
