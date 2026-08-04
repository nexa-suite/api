package com.nexa.api.sales.infrastructure;

import com.nexa.api.sales.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.sales.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.sales.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.sales.application.clientaccount.service.ClientAccountService;
import com.nexa.api.sales.application.port.out.ClientAccountAddressPort;
import com.nexa.api.sales.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.application.port.out.WarehouseReferencePort;
import com.nexa.api.sales.application.purchaserequest.port.*;
import com.nexa.api.sales.application.purchaserequest.service.PurchaseRequestService;
import com.nexa.api.sales.application.reference.port.PeruGeographyPersistencePort;
import com.nexa.api.sales.application.reference.port.PeruGeographyUseCase;
import com.nexa.api.sales.application.reference.service.PeruGeographyService;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderDraftPersistencePort;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.sales.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.sales.application.salesorder.service.SalesOrderService;
import com.nexa.api.sales.application.salesorder.service.ManualSalesOrderService;
import com.nexa.api.sales.application.salesorder.service.ManualSalesOrderDraftService;
import com.nexa.api.sales.application.workflow.SalesSnapshotAssembler;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SalesRuntimeConfiguration {
	@Bean ClientAccountUseCase clientAccountUseCase(ClientAccountPersistencePort port) { return new ClientAccountService(port); }
	@Bean ClientAccountAddressUseCase clientAccountAddressUseCase(ClientAccountAddressPersistencePort persistence,
			ClientAccountCommercialPort accounts) { return new ClientAccountAddressService(persistence, accounts); }
	@Bean PeruGeographyUseCase peruGeographyUseCase(PeruGeographyPersistencePort persistence) {
		return new PeruGeographyService(persistence);
	}
	@Bean SalesSnapshotAssembler salesSnapshotAssembler(ClientAccountCommercialPort accounts,
			ClientAccountAddressPort addresses, WarehouseReferencePort warehouses, PeruGeographyPersistencePort geography,
			MapRoutingPort maps, CatalogItemSnapshotLookupPort catalog,
			com.nexa.api.sales.application.purchaserequest.port.SellableSkuSnapshotLookupPort sellableSkus) {
		return new SalesSnapshotAssembler(accounts, addresses, warehouses, geography, maps, catalog, sellableSkus);
	}
	@Bean ManualSalesOrderUseCase manualSalesOrderUseCase(SalesSnapshotAssembler snapshots,
			ManualSalesOrderPersistencePort persistence) { return new ManualSalesOrderService(snapshots, persistence); }
	@Bean ManualSalesOrderDraftUseCase manualSalesOrderDraftUseCase(ManualSalesOrderDraftPersistencePort drafts,
			ManualSalesOrderUseCase manualOrders, ManualSalesOrderPersistencePort orders) {
		return new ManualSalesOrderDraftService(drafts, manualOrders, orders);
	}
	@Bean PurchaseRequestUseCase purchaseRequestUseCase(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, ClientAccountPersistencePort accounts,
			ChangeEventPersistencePort changeFeed) {
		return new PurchaseRequestService(persistence, events, idempotency, catalog, accounts, changeFeed);
	}
	@Bean SalesOrderUseCase salesOrderUseCase(SalesOrderPersistencePort persistence, SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence, ClientAccountPersistencePort accounts) { return new SalesOrderService(persistence, accounts, aggregatePersistence, conversionPersistence); }
}
