package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrant;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderProjection;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderReadQuery;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderReadQueryFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/** Maps one BC01-approved order grant to the minimized Tenant-local BC04 projection. */
public final class TenantBusinessDatabaseSupportSalesOrderReadQueryFactory
		implements SupportSalesOrderReadQueryFactory {
	private final TenantBusinessDatabaseSupportOrderReadRouter router;

	public TenantBusinessDatabaseSupportSalesOrderReadQueryFactory(TenantBusinessDatabaseSupportOrderReadRouter router) {
		this.router = Objects.requireNonNull(router);
	}

	@Override
	public SupportSalesOrderReadQuery open(SupportOrderReadGrant grant) {
		Objects.requireNonNull(grant, "An approved support read grant is required");
		return () -> router.read(grant, jdbc -> readProjection(jdbc, grant));
	}

	private static SupportSalesOrderProjection readProjection(JdbcTemplate jdbc, SupportOrderReadGrant grant) {
		return jdbc.query("select status,total_amount,currency,version from sales.sales_order "
					+ "where id=? and tenant_id=? and workspace_id=?",
				(rs, row) -> new SupportSalesOrderProjection(rs.getString("status"),
						rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getLong("version")),
				grant.salesOrderId(), grant.tenantId(), grant.workspaceId())
				.stream().findFirst().orElseThrow(() -> new ApiResourceNotFoundException("sales order"));
	}
}
