package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderSupportProjectionQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds the Sales-owned support projection query to one router-owned Tenant session. */
@FunctionalInterface
public interface TenantSalesOrderSupportProjectionQueryFactory {
	SalesOrderSupportProjectionQuery bindTo(JdbcTemplate tenantJdbc);
}
