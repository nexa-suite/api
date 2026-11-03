package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSummaryExportFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Selects Tenant-routed order exports when local Tenant business routing is enabled. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled",
		havingValue = "true", matchIfMissing = false)
public class TenantBoundSalesOrderSummaryExportConfiguration {
	@Bean
	@Primary
	SalesOrderSummaryExportUseCase tenantBoundSalesOrderSummaryExportUseCase(TenantBusinessDatabaseRouter router,
			TenantCustomerAccountQueryFactory customerAccounts, TenantSalesOrderSummaryExportFactory exportFactory) {
		return new TenantBoundSalesOrderSummaryExportUseCase(router, customerAccounts, exportFactory);
	}
}
