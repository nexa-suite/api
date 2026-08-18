package com.nexa.api.bootstrap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Provisions the scoped technical membership for workspaces created after V59. */
@Component
@Profile("!test")
public class SystemWorkflowActorBootstrap {
    private static final String ACTOR = "11111111-1111-4111-8111-111111111111";
    private static final String ROLE = "22222222-2222-4222-8222-222222222222";
    private final JdbcTemplate jdbc;

    public SystemWorkflowActorBootstrap(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE - 25)
    @Transactional
    public void provision() {
        jdbc.query("select id,tenant_id from tenant_management.workspace where status='ACTIVE'", (rs, row) -> {
            String workspace = rs.getObject("id").toString();
            String tenant = rs.getObject("tenant_id").toString();
            String membership = jdbc.queryForObject("select md5('nexa-system-workflow:' || ?::text)::uuid", String.class, workspace);
            jdbc.update("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?::uuid,?::uuid,?::uuid,'SYSTEM_WORKFLOW','ACTIVE',current_timestamp,current_timestamp,0) on conflict (workspace_id,user_id) do update set membership_type='SYSTEM_WORKFLOW',status='ACTIVE',updated_at=current_timestamp", membership, workspace, ACTOR);
            jdbc.update("insert into tenant_management.membership_role_definition (membership_id,tenant_id,workspace_id,role_id,assigned_at) values (?::uuid,?::uuid,?::uuid,?::uuid,current_timestamp) on conflict do nothing", membership, tenant, workspace, ROLE);
            jdbc.update("insert into tenant_management.membership_authorization_state (membership_id,tenant_id,workspace_id,authorization_version,updated_at) values (?::uuid,?::uuid,?::uuid,0,current_timestamp) on conflict (membership_id) do nothing", membership, tenant, workspace);
            return null;
        });
    }
}
