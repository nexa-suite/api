package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerMembershipQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcCustomerMembershipQuery implements CustomerMembershipQuery {
    private final JdbcTemplate jdbc;

    public JdbcCustomerMembershipQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> findMembershipIds(UUID tenantId, UUID workspaceId, UUID clientAccountId) {
        return jdbc.query("select workspace_membership_id from sales.client_account_membership "
                        + "where tenant_id=? and workspace_id=? and client_account_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, clientAccountId);
    }
}
