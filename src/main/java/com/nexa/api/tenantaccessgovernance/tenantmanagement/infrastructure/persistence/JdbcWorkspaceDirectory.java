package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceScopeReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcWorkspaceDirectory implements WorkspaceDirectory {
    private final JdbcTemplate jdbc;
    private final WorkspaceScopeReader scopeReader;

    @Autowired
    public JdbcWorkspaceDirectory(JdbcTemplate jdbc, WorkspaceScopeReader scopeReader) {
        this.jdbc = jdbc;
        this.scopeReader = scopeReader;
    }

    /** Compatibility constructor for isolated callers that only need exact scope lookup. */
    public JdbcWorkspaceDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.scopeReader = null;
    }

    @Override
    public boolean exists(UUID tenantId, UUID workspaceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tenant_management.workspace where tenant_id=? and id=?)",
                Boolean.class, tenantId, workspaceId));
    }

    @Override
    public List<Scope> scanAfter(UUID tenantId, UUID workspaceId, int limit) {
        return scopeReader().scanAfter(tenantId, workspaceId, limit);
    }

    @Override
    public List<Scope> scanActiveAfter(UUID tenantId, UUID workspaceId, int limit) {
        return scopeReader().scanActiveAfter(tenantId, workspaceId, limit);
    }

    private WorkspaceScopeReader scopeReader() {
        if (scopeReader == null) {
            throw new IllegalStateException("Dedicated Workspace scope-reader credentials are required for global enumeration");
        }
        if (!RlsRequestScope.crossScopeWorkspaceScanEnabled()) {
            throw new IllegalStateException("Explicit worker workspace enumeration scope is required");
        }
        return scopeReader;
    }
}
