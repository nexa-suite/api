package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.WarehouseAccessGrantPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.ConcurrencyConflictException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.WarehouseAccessGrant;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.WarehouseAccessGrantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.stream.Collectors;

/** BC-01-owned JDBC persistence for Warehouse access grants. */
@Repository
@Profile("!test")
public class JdbcWarehouseAccessGrantPersistenceAdapter implements WarehouseAccessGrantPersistencePort {
    private static final String SELECT = "select tenant_id,workspace_id,membership_id,warehouse_id,status,version,changed_by_membership_id,changed_at "
            + "from tenant_management.warehouse_access_grant ";
    private static final UUID SYSTEM_WORKFLOW_USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SYSTEM_WORKFLOW_ROLE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String VERIFIED_SYSTEM_WORKFLOW_TARGET = "m.membership_type='SYSTEM_WORKFLOW' "
            + "and m.user_id=? and exists (select 1 from iam.user_account u "
            + "join tenant_management.membership_role_definition mr on mr.membership_id=m.id "
            + "and mr.tenant_id=t.id and mr.workspace_id=w.id "
            + "join tenant_management.role_definition r on r.id=mr.role_id "
            + "where u.id=m.user_id and u.status='ACTIVE' and u.username=? and u.normalized_email=? "
            + "and r.id=? and r.code=? and r.role_type='SYSTEM_RESERVED' and r.status='ACTIVE' "
            + "and r.tenant_id is null and r.workspace_id is null)";

    private final JdbcTemplate jdbc;

    public JdbcWarehouseAccessGrantPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActiveScope(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from tenant_management.tenant t "
                        + "join tenant_management.workspace w on w.tenant_id=t.id "
                        + "join tenant_management.workspace_membership m on m.workspace_id=w.id "
                        + "where t.id=? and t.status='ACTIVE' and w.id=? and w.status='ACTIVE' "
                        + "and m.id=? and m.status='ACTIVE' and m.membership_type='INTERNAL')", Boolean.class,
                tenantId.value(), workspaceId.value(), membershipId.value()));
    }

    @Override
    public boolean isActiveMembership(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from tenant_management.tenant t "
                        + "join tenant_management.workspace w on w.tenant_id=t.id "
                        + "join tenant_management.workspace_membership m on m.workspace_id=w.id "
                        + "where t.id=? and t.status='ACTIVE' and w.id=? and w.status='ACTIVE' "
                        + "and m.id=? and m.status='ACTIVE' and (m.membership_type='INTERNAL' or ("
                        + VERIFIED_SYSTEM_WORKFLOW_TARGET + "))) ", Boolean.class,
                membershipScopeParameters(tenantId, workspaceId, membershipId)));
    }

    @Override
    public Set<UUID> activeWarehouseIds(TenantId tenantId, WorkspaceId workspaceId, MembershipId membershipId) {
        String sql = "select distinct g.warehouse_id from tenant_management.warehouse_access_grant g "
                        + "join tenant_management.tenant t on t.id=g.tenant_id and t.status='ACTIVE' "
                        + "join tenant_management.workspace w on w.tenant_id=g.tenant_id and w.id=g.workspace_id and w.status='ACTIVE' "
                        + "join tenant_management.workspace_membership m on m.workspace_id=g.workspace_id "
                        + "and m.id=g.membership_id and m.status='ACTIVE' "
                        + "where g.tenant_id=? and g.workspace_id=? and g.membership_id=? "
                        + "and (m.membership_type='INTERNAL' or (" + VERIFIED_SYSTEM_WORKFLOW_TARGET + ")) "
                        + "and g.status='ACTIVE' order by g.warehouse_id";
        return jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class),
                        membershipScopeParameters(tenantId, workspaceId, membershipId))
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<WarehouseAccessGrant> findForWarehouse(TenantId tenantId, WorkspaceId workspaceId, UUID warehouseId) {
        return jdbc.query(SELECT + "where tenant_id=? and workspace_id=? and warehouse_id=? order by membership_id",
                JdbcWarehouseAccessGrantPersistenceAdapter::grant, tenantId.value(), workspaceId.value(), warehouseId);
    }

    @Override
    public Optional<WarehouseAccessGrant> find(TenantId tenantId, WorkspaceId workspaceId,
                                                MembershipId membershipId, UUID warehouseId) {
        return jdbc.query(SELECT + "where tenant_id=? and workspace_id=? and membership_id=? and warehouse_id=?",
                rs -> rs.next() ? Optional.of(grant(rs, 0)) : Optional.empty(), tenantId.value(), workspaceId.value(),
                membershipId.value(), warehouseId);
    }

    @Override
    @Transactional
    public WarehouseAccessGrant insert(WarehouseAccessGrant grant) {
        try {
            jdbc.update("insert into tenant_management.warehouse_access_grant "
                            + "(tenant_id,workspace_id,membership_id,warehouse_id,status,version,changed_by_membership_id,changed_at) "
                            + "values (?,?,?,?,?,?,?,?)",
                    grant.tenantId().value(), grant.workspaceId().value(), grant.membershipId().value(), grant.warehouseId(),
                    grant.status().name(), grant.version(), grant.changedBy().value(), Timestamp.from(grant.changedAt()));
        } catch (DuplicateKeyException exception) {
            throw new ConcurrencyConflictException();
        }
        return grant;
    }

    @Override
    @Transactional
    public int update(WarehouseAccessGrant grant, long expectedVersion) {
        return jdbc.update("update tenant_management.warehouse_access_grant set status=?,version=?,changed_by_membership_id=?,changed_at=? "
                        + "where tenant_id=? and workspace_id=? and membership_id=? and warehouse_id=? and version=?",
                grant.status().name(), grant.version(), grant.changedBy().value(), Timestamp.from(grant.changedAt()),
                grant.tenantId().value(), grant.workspaceId().value(), grant.membershipId().value(), grant.warehouseId(),
                expectedVersion);
    }

    private static WarehouseAccessGrant grant(ResultSet rs, int row) throws SQLException {
        return new WarehouseAccessGrant(new TenantId(rs.getObject("tenant_id", UUID.class)),
                new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
                new MembershipId(rs.getObject("membership_id", UUID.class)), rs.getObject("warehouse_id", UUID.class),
                WarehouseAccessGrantStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                new MembershipId(rs.getObject("changed_by_membership_id", UUID.class)),
                rs.getTimestamp("changed_at").toInstant());
    }

    private static Object[] membershipScopeParameters(TenantId tenantId, WorkspaceId workspaceId,
                                                       MembershipId membershipId) {
        List<Object> parameters = new ArrayList<>(List.of(tenantId.value(), workspaceId.value(), membershipId.value(),
                SYSTEM_WORKFLOW_USER_ID, "NEXA_AUTOMATION", "nexa-automation@system.invalid",
                SYSTEM_WORKFLOW_ROLE_ID, "system_workflow"));
        return parameters.toArray();
    }
}
