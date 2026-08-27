package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAddressQuery;
import com.nexa.api.salescommitment.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.salescommitment.application.port.out.MapRoutingPort;
import com.nexa.api.salescommitment.application.port.out.WarehouseReferencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.*;
import com.nexa.api.salescommitment.application.purchaserequest.service.PurchaseRequestService;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.directorder.service.DirectOrderService;
import com.nexa.api.salescommitment.application.reference.port.PeruGeographyPersistencePort;
import com.nexa.api.salescommitment.application.reference.port.PeruGeographyUseCase;
import com.nexa.api.salescommitment.application.reference.service.PeruGeographyService;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.service.SalesOrderService;
import com.nexa.api.salescommitment.application.salesorder.service.ManualSalesOrderService;
import com.nexa.api.salescommitment.application.salesorder.service.ManualSalesOrderDraftService;
import com.nexa.api.salescommitment.application.workflow.SalesSnapshotAssembler;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SalesRuntimeConfiguration {
	@Bean PeruGeographyUseCase peruGeographyUseCase(PeruGeographyPersistencePort persistence) {
		return new PeruGeographyService(persistence);
	}
	@Bean SalesSnapshotAssembler salesSnapshotAssembler(ClientAccountCommercialPort accounts,
			CustomerAddressQuery addresses, WarehouseReferencePort warehouses, PeruGeographyPersistencePort geography,
			MapRoutingPort maps, CatalogItemSnapshotLookupPort catalog,
			com.nexa.api.salescommitment.application.purchaserequest.port.SellableSkuSnapshotLookupPort sellableSkus) {
		return new SalesSnapshotAssembler(accounts, addresses, warehouses, geography, maps, catalog, sellableSkus);
	}
	@Bean ManualSalesOrderUseCase manualSalesOrderUseCase(SalesSnapshotAssembler snapshots,
			ManualSalesOrderPersistencePort persistence) { return new ManualSalesOrderService(snapshots, persistence); }
	@Bean ManualSalesOrderDraftUseCase manualSalesOrderDraftUseCase(ManualSalesOrderDraftPersistencePort drafts,
			ManualSalesOrderUseCase manualOrders, ManualSalesOrderPersistencePort orders) {
		return new ManualSalesOrderDraftService(drafts, manualOrders, orders);
	}
	@Bean PurchaseRequestUseCase purchaseRequestUseCase(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, CustomerAccountQuery accounts,
				ChangeEventPersistencePort changeFeed, com.nexa.api.salescommitment.application.port.CommercialCommitmentPort commitments,
				Clock clock, ObjectMapper objectMapper) {
		return new PurchaseRequestService(persistence, events, idempotency, catalog, accounts, changeFeed, commitments, clock, objectMapper);
	}
	@Bean SalesOrderUseCase salesOrderUseCase(SalesOrderPersistencePort persistence, SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence, CustomerAccountQuery accounts, IdempotencyPersistencePort idempotency, ObjectMapper objectMapper) { return new SalesOrderService(persistence, accounts, aggregatePersistence, conversionPersistence, idempotency, objectMapper); }
	@Bean DirectOrderUseCase directOrderUseCase(com.nexa.api.salescommitment.application.port.CommercialCommitmentPort commitments,
			SalesOrderPersistencePort orders, Clock clock, IdempotencyPersistencePort idempotency, ObjectMapper objectMapper,
			SellableSkuQuery sellableSkus) {
		return new DirectOrderService(commitments, orders, clock, idempotency, objectMapper, sellableSkus);
	}
}
