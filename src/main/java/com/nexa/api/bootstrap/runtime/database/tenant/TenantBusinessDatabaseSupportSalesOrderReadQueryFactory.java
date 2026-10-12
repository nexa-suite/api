package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderSupportProjectionQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSupportProjectionQueryFactory;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrant;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderProjection;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderReadQuery;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderReadQueryFactory;

import java.util.Objects;

/** Routes one BC01-approved grant and maps the Sales-owned Tenant projection to BC01's public shape. */
public final class TenantBusinessDatabaseSupportSalesOrderReadQueryFactory
		implements SupportSalesOrderReadQueryFactory {
	private final TenantBusinessDatabaseSupportOrderReadRouter router;
	private final TenantSalesOrderSupportProjectionQueryFactory salesOrders;

	public TenantBusinessDatabaseSupportSalesOrderReadQueryFactory(TenantBusinessDatabaseSupportOrderReadRouter router,
			TenantSalesOrderSupportProjectionQueryFactory salesOrders) {
		this.router = Objects.requireNonNull(router);
		this.salesOrders = Objects.requireNonNull(salesOrders);
	}

	@Override
	public SupportSalesOrderReadQuery open(SupportOrderReadGrant grant) {
		Objects.requireNonNull(grant, "An approved support read grant is required");
		return () -> router.read(grant, jdbc -> {
			SalesOrderSupportProjectionQuery.Snapshot projection = salesOrders.bindTo(jdbc)
					.find(grant.tenantId(), grant.workspaceId(), grant.salesOrderId());
			return new SupportSalesOrderProjection(projection.status(), projection.total(), projection.currency(),
					projection.version());
		});
	}
}
