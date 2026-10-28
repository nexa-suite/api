package com.nexa.api.bootstrap.runtime;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceScopeReader;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Provisions the scoped technical membership for workspaces created after V59. */
@Component
@Profile("!test & !local-fixtures")
public class SystemWorkflowActorBootstrap {
    private static final String ACTOR = "11111111-1111-4111-8111-111111111111";
    private static final String ROLE = "22222222-2222-4222-8222-222222222222";
    private final JdbcTemplate jdbc;
    private final WorkspaceScopeReader workspaceScopeReader;

    public SystemWorkflowActorBootstrap(JdbcTemplate jdbc, WorkspaceScopeReader workspaceScopeReader) {
        this.jdbc = jdbc;
        this.workspaceScopeReader = workspaceScopeReader;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 25)
    @Transactional
    public void provision() {
        List<WorkspaceDirectory.Scope> workspaces = new ArrayList<>();
        UUID cursorTenant = null;
        UUID cursorWorkspace = null;
        RlsRequestScope.enableCrossScopeWorkspaceScan();
        try {
            while (true) {
                List<WorkspaceDirectory.Scope> page = workspaceScopeReader.scanActiveWorkspacesAfter(
                        cursorTenant, cursorWorkspace, 100);
                workspaces.addAll(page);
                if (page.size() < 100) break;
                WorkspaceDirectory.Scope last = page.getLast();
                cursorTenant = last.tenantId();
                cursorWorkspace = last.workspaceId();
            }
        } finally {
            RlsRequestScope.clearCrossScopeWorkspaceScan();
        }
        try {
            for (WorkspaceDirectory.Scope scope : workspaces) {
                setTransactionScope(scope);
                String workspace = scope.workspaceId().toString();
                String tenant = scope.tenantId().toString();
                String membership = jdbc.queryForObject("select md5('nexa-system-workflow:' || ?::text)::uuid", String.class, workspace);
                int inserted = jdbc.update("insert into tenant_management.workspace_membership (id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?::uuid,?::uuid,?::uuid,?::uuid,'SYSTEM_WORKFLOW','ACTIVE',current_timestamp,current_timestamp,0) on conflict (workspace_id,user_id) do nothing", membership, tenant, workspace, ACTOR);
                if (inserted == 1) {
                    jdbc.update("insert into tenant_management.membership_role_definition (membership_id,tenant_id,workspace_id,role_id,assigned_at) values (?::uuid,?::uuid,?::uuid,?::uuid,current_timestamp) on conflict do nothing", membership, tenant, workspace, ROLE);
                    jdbc.update("insert into tenant_management.membership_authorization_state (membership_id,tenant_id,workspace_id,authorization_version,updated_at) values (?::uuid,?::uuid,?::uuid,0,current_timestamp) on conflict (membership_id) do nothing", membership, tenant, workspace);
                }
            }
        } finally {
            clearTransactionScope();
        }
    }

    private void setTransactionScope(WorkspaceDirectory.Scope scope) {
        jdbc.queryForObject("select set_config('app.current_tenant_id', ?, true) || set_config('app.current_workspace_id', ?, true)",
                String.class, scope.tenantId().toString(), scope.workspaceId().toString());
    }

    private void clearTransactionScope() {
        jdbc.queryForObject("select set_config('app.current_tenant_id', '', true) || set_config('app.current_workspace_id', '', true)", String.class);
    }
}
