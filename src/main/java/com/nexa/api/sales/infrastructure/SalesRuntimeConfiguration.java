package com.nexa.api.sales.infrastructure;

import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.sales.application.clientaccount.service.ClientAccountService;
import com.nexa.api.sales.application.purchaserequest.port.*;
import com.nexa.api.sales.application.purchaserequest.service.PurchaseRequestService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SalesRuntimeConfiguration {
	@Bean ClientAccountUseCase clientAccountUseCase(ClientAccountPersistencePort port) { return new ClientAccountService(port); }
	@Bean PurchaseRequestUseCase purchaseRequestUseCase(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, ClientAccountPersistencePort accounts) {
		return new PurchaseRequestService(persistence, events, idempotency, catalog, accounts);
	}
}
