package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportFormat;
import com.nexa.api.salescommitment.application.salesorder.export.model.SalesOrderSummaryExportResult;
import com.nexa.api.salescommitment.application.salesorder.export.port.SalesOrderSummaryExportUseCase;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderSummaryExportFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Selects Tenant-routed order exports for the enabled runtime and surface. */
@Configuration(proxyBeanMethods = false)
public class TenantBoundSalesOrderSummaryExportConfiguration {
	@Bean
	@Primary
	@Profile("local")
	@ConditionalOnProperty(prefix = "nexa.tenant-business.purchase-request", name = "enabled", havingValue = "true")
	SalesOrderSummaryExportUseCase tenantBoundSalesOrderSummaryExportUseCase(TenantBusinessDatabaseRouter router,
			TenantCustomerAccountQueryFactory customerAccounts, TenantSalesOrderSummaryExportFactory exportFactory) {
		return new TenantBoundSalesOrderSummaryExportUseCase(router, customerAccounts, exportFactory);
	}

	@Bean
	@Profile("tenant-cloud")
	@ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-buyer-journey-read", name = "enabled", havingValue = "true")
	CloudBuyerExports cloudBuyerExports(TenantBusinessDatabaseRouter router,
			TenantCustomerAccountQueryFactory customerAccounts, TenantSalesOrderSummaryExportFactory exportFactory) {
		var exports = new TenantBoundSalesOrderSummaryExportUseCase(router, customerAccounts, exportFactory);
		return new CloudBuyerExports(exports::exportReadOnly);
	}

	record CloudBuyerExports(SalesOrderSummaryExportUseCase reader) {
		SalesOrderSummaryExportUseCase withFallback(SalesOrderSummaryExportUseCase fallback) {
			return new SalesOrderSummaryExportUseCase() {
				@Override
				public SalesOrderSummaryExportResult export(CurrentAccessContext context, String orderId,
						SalesOrderSummaryExportFormat format) {
					return fallback.export(context, orderId, format);
				}

				@Override
				public SalesOrderSummaryExportResult exportBuyer(CurrentAccessContext context, String orderId,
						SalesOrderSummaryExportFormat format) {
					if (context != null && context.surface() == Surface.PORTAL && context.hasRole(MembershipRole.BUYER)) {
						return reader.export(context, orderId, format);
					}
					return fallback.exportBuyer(context, orderId, format);
				}
			};
		}
	}
}
