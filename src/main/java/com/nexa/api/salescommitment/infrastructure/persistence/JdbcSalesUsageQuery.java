package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesUsageQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Reads tenant transaction usage from Sales-owned tables. */
@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcSalesUsageQuery implements SalesUsageQuery {
    private final JdbcTemplate jdbc;

    public JdbcSalesUsageQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public long countTransactions(UUID tenantId) {
        Long value = jdbc.queryForObject("select (select count(*) from sales.purchase_request where tenant_id=?) + "
                + "(select count(*) from sales.sales_order where tenant_id=?)", Long.class, tenantId, tenantId);
        return value == null ? 0 : value;
    }
}
