package com.nexa.api.edge.streaming.infrastructure;

import com.nexa.api.edge.streaming.ChangeFeedQueryPort;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeFeedQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Binds the existing change-feed query adapter to one Tenant transaction. */
@Component
@Profile("!test")
public final class JdbcTenantChangeFeedQueryFactory implements TenantChangeFeedQueryFactory {
    @Override
    public ChangeFeedQueryPort bindTo(JdbcTemplate tenantJdbc) {
        return new ChangeFeedQueryAdapter(Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"));
    }
}
