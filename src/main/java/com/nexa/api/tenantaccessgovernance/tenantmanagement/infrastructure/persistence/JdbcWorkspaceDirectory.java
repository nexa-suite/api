package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkspaceDirectory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcWorkspaceDirectory implements WorkspaceDirectory {
    private final JdbcTemplate jdbc;
    public JdbcWorkspaceDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean exists(UUID tenantId, UUID workspaceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tenant_management.workspace where tenant_id=? and id=?)",
                Boolean.class, tenantId, workspaceId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Scope> scanAfter(UUID tenantId, UUID workspaceId, int limit) {
        if (!RlsRequestScope.crossScopeWorkspaceScanEnabled()) {
            throw new IllegalStateException("Explicit worker workspace enumeration scope is required");
        }
        jdbc.queryForObject("select set_config('app.cross_scope_workspace_scan', 'true', true)", String.class);
        int boundedLimit = Math.min(100, Math.max(1, limit));
        return tenantId == null
                ? jdbc.query("select tenant_id,id from tenant_management.workspace order by tenant_id,id limit ?",
                (rs, row) -> new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), boundedLimit)
                : jdbc.query("select tenant_id,id from tenant_management.workspace where (tenant_id,id) > (?,?) order by tenant_id,id limit ?",
                (rs, row) -> new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), tenantId, workspaceId, boundedLimit);
    }
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Scope> scanActiveAfter(UUID tenantId, UUID workspaceId, int limit) {
        if (!RlsRequestScope.crossScopeWorkspaceScanEnabled()) {
            throw new IllegalStateException("Explicit worker workspace enumeration scope is required");
        }
        jdbc.queryForObject("select set_config('app.cross_scope_workspace_scan', 'true', true)", String.class);
        int boundedLimit = Math.min(100, Math.max(1, limit));
        String sql = "select w.tenant_id,w.id from tenant_management.workspace w "
                + "join tenant_management.tenant t on t.id=w.tenant_id where t.status='ACTIVE' and w.status='ACTIVE'";
        return tenantId == null
                ? jdbc.query(sql + " order by w.tenant_id,w.id limit ?",
                        (rs, row) -> new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), boundedLimit)
                : jdbc.query(sql + " and (w.tenant_id,w.id) > (?,?) order by w.tenant_id,w.id limit ?",
                        (rs, row) -> new Scope(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), tenantId, workspaceId, boundedLimit);
    }
}
