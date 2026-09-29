package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.LegacyCustomerCreditInitializationQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcLegacyCustomerCreditInitializationQuery implements LegacyCustomerCreditInitializationQuery {
    private final JdbcTemplate jdbc;
    public JdbcLegacyCustomerCreditInitializationQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Snapshot> find(UUID tenantId, UUID workspaceId, UUID customerAccountId, String currency) {
        return jdbc.query("select id,credit_currency,credit_limit from sales.client_account where tenant_id=? and workspace_id=? and id=? and credit_currency=?",
                (rs, row) -> new Snapshot(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3)),
                tenantId, workspaceId, customerAccountId, currency).stream().findFirst();
    }
}
