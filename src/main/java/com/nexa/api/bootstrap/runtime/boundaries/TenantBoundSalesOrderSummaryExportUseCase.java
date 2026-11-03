package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportResult;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSummaryExportFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.Objects;

/** Routes order export and Buyer relationship reads through one verified Tenant transaction. */
public final class TenantBoundSalesOrderSummaryExportUseCase implements SalesOrderSummaryExportUseCase {
	private final TenantBusinessDatabaseRouter router;
	private final TenantCustomerAccountQueryFactory customerAccounts;
	private final TenantSalesOrderSummaryExportFactory exportFactory;

	public TenantBoundSalesOrderSummaryExportUseCase(TenantBusinessDatabaseRouter router,
			TenantCustomerAccountQueryFactory customerAccounts, TenantSalesOrderSummaryExportFactory exportFactory) {
		this.router = Objects.requireNonNull(router, "Tenant business database router is required");
		this.customerAccounts = Objects.requireNonNull(customerAccounts,
				"Tenant Customer Account query factory is required");
		this.exportFactory = Objects.requireNonNull(exportFactory, "Tenant sales order summary export factory is required");
	}

	@Override
	public SalesOrderSummaryExportResult export(CurrentAccessContext context, String orderId,
			SalesOrderSummaryExportFormat format) {
		SalesOrderSummaryExportUseCase.requireExportPermission(context);
		Objects.requireNonNull(format, "Export format is required");
		return router.inTransaction(context, tenantJdbc -> {
			CustomerAccountQuery tenantAccounts = Objects.requireNonNull(customerAccounts.bindTo(tenantJdbc),
					"Tenant Customer Account query factory returned no query");
			SalesOrderSummaryExportUseCase tenantExports = Objects.requireNonNull(
					exportFactory.bindTo(tenantJdbc, tenantAccounts),
					"Tenant sales order summary export factory returned no use case");
			return tenantExports.export(context, orderId, format);
		});
	}
}
