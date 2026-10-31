package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import org.springframework.jdbc.core.JdbcTemplate;

/** Composes the BC-04 export use case with queries bound to one router-owned Tenant session. */
@FunctionalInterface
public interface TenantSalesOrderSummaryExportFactory {
	SalesOrderSummaryExportUseCase bindTo(JdbcTemplate tenantJdbc, CustomerAccountQuery tenantAccounts);
}
