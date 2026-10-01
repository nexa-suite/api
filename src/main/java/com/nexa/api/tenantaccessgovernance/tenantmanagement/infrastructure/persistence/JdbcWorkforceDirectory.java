package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcWorkforceDirectory implements WorkforceDirectory {
    private final JdbcTemplate jdbc;

    public JdbcWorkforceDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean membershipExists(UUID tenantId, UUID workspaceId, UUID membershipId) {
        Boolean exists = jdbc.queryForObject("select exists(select 1 from tenant_management.workspace_membership m "
                        + "join tenant_management.workspace w on w.id=m.workspace_id "
                        + "where w.tenant_id=? and m.workspace_id=? and m.id=?)",
                Boolean.class, tenantId, workspaceId, membershipId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<String> findAssignableLogisticsName(UUID tenantId, UUID workspaceId, UUID membershipId) {
        return jdbc.query("select u.display_name from tenant_management.workspace_membership m "
                        + "join tenant_management.workspace w on w.id=m.workspace_id "
                        + "join iam.user_account u on u.id=m.user_id "
                        + "join tenant_management.membership_role_definition a on a.membership_id=m.id "
                        + "join tenant_management.role_definition r on r.id=a.role_id "
                        + "where m.id=? and w.tenant_id=? and w.id=? and r.code='logistics' "
                        + "and r.status='ACTIVE' and m.status='ACTIVE'",
                (ResultSetExtractor<Optional<String>>) rs -> rs.next()
                        ? Optional.ofNullable(rs.getString(1)) : Optional.empty(),
                membershipId, tenantId, workspaceId);
    }

    @Override
    public List<LogisticsAssignee> findLogisticsAssignees(UUID tenantId, UUID workspaceId) {
        return jdbc.query("select m.id,m.user_id,u.email,u.display_name from tenant_management.workspace_membership m "
                        + "join tenant_management.workspace w on w.id=m.workspace_id "
                        + "join iam.user_account u on u.id=m.user_id "
                        + "where w.tenant_id=? and m.workspace_id=? and m.membership_type='INTERNAL' and m.status='ACTIVE' "
                        + "and exists (select 1 from tenant_management.membership_role_definition a "
                        + "join tenant_management.role_definition r on r.id=a.role_id "
                        + "where a.membership_id=m.id and r.code='logistics' and r.status='ACTIVE') "
                        + "order by u.display_name,u.email,m.id",
                (rs, row) -> new LogisticsAssignee(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4)),
                tenantId, workspaceId);
    }

    @Override
    public Set<UUID> filterActiveBuyerMembershipIds(UUID tenantId, UUID workspaceId, List<UUID> membershipIds) {
        if (membershipIds == null || membershipIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(membershipIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(2 + membershipIds.size());
        parameters.add(tenantId);
        parameters.add(workspaceId);
        parameters.addAll(membershipIds);
        return Set.copyOf(jdbc.query("select distinct m.id from tenant_management.workspace_membership m "
                        + "join tenant_management.workspace w on w.id=m.workspace_id "
                        + "where w.tenant_id=? and m.workspace_id=? and m.id in (" + placeholders + ") "
                        + "and m.status='ACTIVE' and m.membership_type='BUYER'",
                (rs, row) -> rs.getObject(1, UUID.class), parameters.toArray()));
    }
}
