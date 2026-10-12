package com.nexa.api.edge.streaming.tenantdatabase;

import com.nexa.api.edge.streaming.ChangeFeedQueryPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds edge-owned change-feed reads to the exact routed Tenant JDBC session. */
@FunctionalInterface
public interface TenantChangeFeedQueryFactory {
    ChangeFeedQueryPort bindTo(JdbcTemplate tenantJdbc);
}
