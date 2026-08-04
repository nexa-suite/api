package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.tenantmanagement.application.port.out.TenantEventContextQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** ACL adapter for scoped memberships and the non-interactive workflow actor. */
@Repository
@Profile("!test")
public class JdbcTenantEventContextQueryAdapter implements TenantEventContextQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcTenantEventContextQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WorkflowActor findSystemWorkflowActor(UUID tenantId, UUID workspaceId) {
        List<WorkflowActor> matches = jdbc.query("select m.id,m.user_id,r.code,u.username "
                        + "from tenant_management.workspace_membership m "
                        + "join tenant_management.workspace w on w.id=m.workspace_id and w.tenant_id=? "
                        + "join tenant_management.membership_role_definition mr "
                        + "on mr.membership_id=m.id and mr.tenant_id=w.tenant_id and mr.workspace_id=m.workspace_id "
                        + "join tenant_management.role_definition r "
                        + "on r.id=mr.role_id and r.tenant_id is null and r.workspace_id is null "
                        + "join iam.user_account u on u.id=m.user_id "
                        + "where w.id=? and m.status='ACTIVE' and m.membership_type=? "
                        + "and r.code=? and r.status='ACTIVE' and u.status='ACTIVE' "
                        + "and u.username=? and u.normalized_email=?",
                (rs, row) -> new WorkflowActor(rs.getObject("user_id", UUID.class), rs.getObject("id", UUID.class),
                        SYSTEM_WORKFLOW_MEMBERSHIP_TYPE, rs.getString("code"), rs.getString("username")),
                tenantId, workspaceId, SYSTEM_WORKFLOW_MEMBERSHIP_TYPE, SYSTEM_WORKFLOW_ROLE_CODE,
                NEXA_AUTOMATION_IDENTITY, NEXA_AUTOMATION_EMAIL);
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one SYSTEM_WORKFLOW/NEXA_AUTOMATION actor for workspace");
        }
        return matches.get(0);
    }

    @Override
    public Set<UUID> findActiveMembershipIdsByRoleCodes(UUID tenantId, UUID workspaceId, Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) return Set.of();
        List<String> normalizedRoles = roleCodes.stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (normalizedRoles.isEmpty()) return Set.of();

        String placeholders = String.join(",", java.util.Collections.nCopies(normalizedRoles.size(), "?"));
        String sql = "select distinct m.id from tenant_management.workspace_membership m "
                + "join tenant_management.workspace w on w.id=m.workspace_id and w.tenant_id=? "
                + "join tenant_management.membership_role_definition mr "
                + "on mr.membership_id=m.id and mr.tenant_id=w.tenant_id and mr.workspace_id=m.workspace_id "
                + "join tenant_management.role_definition r "
                + "on r.id=mr.role_id and r.tenant_id is null and r.workspace_id is null "
                + "where w.id=? and m.status='ACTIVE' and r.status='ACTIVE' and lower(r.code) in ("
                + placeholders + ")";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(workspaceId);
        args.addAll(normalizedRoles);
        return Set.copyOf(jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class), args.toArray()));
    }
}
