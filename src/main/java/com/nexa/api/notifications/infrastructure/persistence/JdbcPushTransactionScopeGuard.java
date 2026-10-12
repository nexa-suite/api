package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.port.out.PushTransactionScopeGuard;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;
import java.util.UUID;

/** Reads transaction-local RLS scope from the supplied Tenant database connection. */
public final class JdbcPushTransactionScopeGuard implements PushTransactionScopeGuard {
    private final JdbcTemplate jdbc;

    public JdbcPushTransactionScopeGuard(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "Tenant JDBC session is required");
    }

    @Override
    public void verify(UUID tenantId, UUID workspaceId) {
        var scope = jdbc.queryForMap("select current_setting('app.current_tenant_id', true) as tenant_id, "
                + "current_setting('app.current_workspace_id', true) as workspace_id");
        if (!tenantId.toString().equals(scope.get("tenant_id"))
                || !workspaceId.toString().equals(scope.get("workspace_id"))) {
            throw new IllegalStateException("Tenant push transaction lost its verified Tenant/Workspace RLS scope");
        }
    }
}
