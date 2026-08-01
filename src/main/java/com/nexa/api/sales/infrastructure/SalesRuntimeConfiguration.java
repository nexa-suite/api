package com.nexa.api.sales.infrastructure;

import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.sales.application.clientaccount.service.ClientAccountService;
import com.nexa.api.sales.application.purchaserequest.port.*;
import com.nexa.api.sales.application.purchaserequest.service.PurchaseRequestService;
import com.nexa.api.sales.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.sales.application.salesorder.service.SalesOrderService;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SalesRuntimeConfiguration {
	@Bean ClientAccountUseCase clientAccountUseCase(ClientAccountPersistencePort port) { return new ClientAccountService(port); }
	@Bean PurchaseRequestUseCase purchaseRequestUseCase(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, ClientAccountPersistencePort accounts,
			ChangeEventPersistencePort changeFeed) {
		return new PurchaseRequestService(persistence, events, idempotency, catalog, accounts, changeFeed);
	}
	@Bean SalesOrderUseCase salesOrderUseCase(SalesOrderPersistencePort persistence, SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence, ClientAccountPersistencePort accounts) { return new SalesOrderService(persistence, accounts, aggregatePersistence, conversionPersistence); }
}
