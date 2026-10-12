package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderSupportProjectionQuery;
import com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSupportProjectionQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Keeps the minimized support read inside Sales Commitment's SQL ownership boundary. */
@Component
@Profile("!test")
public final class JdbcTenantSalesOrderSupportProjectionQueryFactory
		implements TenantSalesOrderSupportProjectionQueryFactory {
	@Override
	public SalesOrderSupportProjectionQuery bindTo(JdbcTemplate tenantJdbc) {
		JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
		return (tenantId, workspaceId, salesOrderId) -> jdbc.query(
				"select status,total_amount,currency,version from sales.sales_order "
						+ "where id=? and tenant_id=? and workspace_id=?",
				(rs, row) -> new SalesOrderSupportProjectionQuery.Snapshot(rs.getString("status"),
						rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getLong("version")),
				salesOrderId, tenantId, workspaceId).stream().findFirst()
				.orElseThrow(() -> new SalesResourceNotFoundException("sales-order"));
	}
}
