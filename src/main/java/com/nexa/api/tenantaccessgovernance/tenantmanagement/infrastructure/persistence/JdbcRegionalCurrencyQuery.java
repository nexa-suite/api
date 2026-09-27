package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.RegionalCurrencyQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcRegionalCurrencyQuery implements RegionalCurrencyQuery {
    private final JdbcTemplate jdbc;
    public JdbcRegionalCurrencyQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<String> findCurrency(UUID tenantId) {
        return jdbc.query("select currency from tenant_management.regional_settings where tenant_id=?",
                (rs, row) -> rs.getString(1), tenantId).stream().findFirst();
    }
}
