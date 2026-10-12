package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Technical BC-02 composition seam for a query bound to one verified Tenant transaction.
 * This is not a business query contract: callers may pass only the JdbcTemplate received
 * inside the Tenant router callback, and the factory must not resolve authority or connections.
 */
@FunctionalInterface
public interface TenantCustomerAccountQueryFactory {
	CustomerAccountQuery bindTo(JdbcTemplate tenantJdbc);
}
