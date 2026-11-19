package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryReadRunner;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.port.PickingWorkRead;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.port.InventoryLotsRead;
import com.nexa.api.inventoryavailability.application.port.InventoryTransfersRead;
import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner.Requirements;
import com.nexa.api.inventoryavailability.application.port.WarehouseDirectoryRead;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationsRequestRunner;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentWorkListModels;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.model.PhysicalAllocationModels;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class MobileOperationsReadConfiguration {
    @Bean
    FulfillmentDeliveryReadRunner fulfillmentDeliveryReadRunner(FulfillmentDeliveryRequestRunner requests) {
        return requests::execute;
    }

    @Bean
    WarehouseDirectoryRead warehouseDirectoryRead(WarehouseOperationsRequestRunner requests) {
        return warehouseDirectory(requests);
    }

    private static WarehouseDirectoryRead warehouseDirectory(WarehouseOperationsRequestRunner queries) {
        return new WarehouseDirectoryRead() {
            public WarehouseOperationsService.Page<WarehouseOperationsService.WarehouseSummary> list(
                    CurrentAccessContext c, int page, int size, String sort) {
                return queries.execute(c, service -> service.warehouses(c, page, size, sort));
            }

            public WarehouseOperationsService.WarehouseSummary get(CurrentAccessContext c, String id) {
                return queries.execute(c, service -> service.warehouse(c, id));
            }
        };
    }

    @Bean
    InventoryLotsRead inventoryLotsRead(WarehouseOperationsRequestRunner requests) {
        return new InventoryLotsRead() {
            public WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> list(
                    CurrentAccessContext c,
                    String item, String warehouse, String zone, String status, int page, int size, String sort) {
                return requests.execute(c, service -> service.lots(c, item, warehouse, zone, status, page, size, sort));
            }
            public WarehouseOperationsService.LotSummary lot(
                    CurrentAccessContext c, String id) {
                return requests.execute(c, service -> service.lot(c, id));
            }
            public List<WarehouseOperationsService.Availability> warehouseAvailability(
                    CurrentAccessContext c, String warehouse, List<String> ids) {
                return requests.execute(c, service -> service.warehouseAvailability(c, warehouse, ids));
            }
        };
    }

    @Bean
    InventoryTransfersRead inventoryTransfersRead(WarehouseOperationsRequestRunner requests) {
        return (c, source, destination, page, size) -> requests.execute(c,
                service -> service.transfers(c, source, destination, page, size));
    }

    @Bean
    PickingWorkRead pickingWorkRead(FulfillmentDeliveryRequestRunner requests) {
        return new PickingWorkRead() {
            public FulfillmentWorkListModels.Page list(
                    CurrentAccessContext c, int page, int size) {
                return requests.execute(c, FulfillmentDeliveryRequestRunner.Requirements.warehouse(),
                        composition -> composition.workList().list(c, page, size));
            }
            public FulfillmentModels.FulfillmentView fulfillment(
                    CurrentAccessContext c, UUID id) {
                return requests.execute(c, FulfillmentDeliveryRequestRunner.Requirements.warehouse(), composition -> composition.fulfillment().get(c, id));
            }
            public PhysicalAllocationModels.PhysicalAllocationView allocation(
                    CurrentAccessContext c, UUID id) {
                return requests.execute(c, FulfillmentDeliveryRequestRunner.Requirements.warehouse(), composition -> composition.fulfillment().getPhysicalAllocation(c, id));
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("tenant-cloud")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.cloud-operations-read", name = "enabled", havingValue = "true")
    static class CloudReads {
        @Bean
        @Primary
        FulfillmentDeliveryReadRunner cloudFulfillmentDeliveryReadRunner(TenantBusinessDatabaseRouter router,
                TenantFulfillmentDeliveryCompositionProviderAdapter compositions,
                WarehouseObjectAccess warehouses, WorkforceDirectory workforce) {
            var runner = new TenantBoundFulfillmentDeliveryRequestRunner(router, compositions, warehouses, workforce);
            return new FulfillmentDeliveryReadRunner() {
                @Override
                public <T> T read(CurrentAccessContext context, Requirements requirements,
                                  Function<FulfillmentDeliveryComposition, T> query) {
                    return runner.read(context, requirements, query);
                }

                @Override
                public <T> T readWithAccountLocks(CurrentAccessContext context, Requirements requirements,
                                                   Function<FulfillmentDeliveryComposition, T> query) {
                    return runner.readWithAccountLocks(context, requirements, query);
                }
            };
        }

        @Bean
        @Primary
        WarehouseDirectoryRead cloudWarehouseDirectoryRead(TenantBusinessDatabaseRouter router,
                TenantWarehouseOperationsCompositionFactory compositions, TenantCustomerAccountQueryFactory accounts,
                TenantSellableSkuQueryFactory skus, TenantSalesOrderFulfillmentQueryFactory orders,
                TenantFulfillmentInventoryQueryFactory fulfillment, TenantBusinessEvidenceQueryFactory evidence,
                TenantChangeEventPersistenceFactory events, TenantBusinessTraceabilityBindingsFactory traceability,
                WarehouseObjectAccess warehouses, OperationalSettingsAccess settings) {
            var runner = new TenantBoundWarehouseOperationsRequestRunner(router, compositions, accounts, skus,
                    orders, fulfillment, evidence, events, traceability, warehouses, settings);
            return warehouseDirectory(runner::read);
        }
        @Bean
        @Primary
        InventoryLotsRead cloudInventoryLotsRead(TenantBusinessDatabaseRouter router,
                TenantWarehouseOperationsCompositionFactory compositions, TenantCustomerAccountQueryFactory accounts,
                TenantSellableSkuQueryFactory skus, TenantSalesOrderFulfillmentQueryFactory orders,
                TenantFulfillmentInventoryQueryFactory fulfillment, TenantBusinessEvidenceQueryFactory evidence,
                TenantChangeEventPersistenceFactory events, TenantBusinessTraceabilityBindingsFactory traceability,
                WarehouseObjectAccess warehouses, OperationalSettingsAccess settings) {
            var runner = new TenantBoundWarehouseOperationsRequestRunner(router, compositions, accounts, skus,
                    orders, fulfillment, evidence, events, traceability, warehouses, settings);
            return new InventoryLotsRead() {
                public WarehouseOperationsService.Page<WarehouseOperationsService.LotSummary> list(
                        CurrentAccessContext c,
                        String item, String warehouse, String zone, String status, int page, int size, String sort) {
                    return runner.listLots(c, item, warehouse, zone, status, page, size, sort);
                }
                public WarehouseOperationsService.LotSummary lot(
                        CurrentAccessContext c, String id) { return runner.lot(c, id); }
                public List<WarehouseOperationsService.Availability> warehouseAvailability(
                        CurrentAccessContext c, String warehouse, List<String> ids) {
                    return runner.warehouseAvailability(c, warehouse, ids);
                }
            };
        }

        @Bean
        @Primary
        InventoryTransfersRead cloudInventoryTransfersRead(TenantBusinessDatabaseRouter router,
                TenantWarehouseOperationsCompositionFactory compositions, TenantCustomerAccountQueryFactory accounts,
                TenantSellableSkuQueryFactory skus, TenantSalesOrderFulfillmentQueryFactory orders,
                TenantFulfillmentInventoryQueryFactory fulfillment, TenantBusinessEvidenceQueryFactory evidence,
                TenantChangeEventPersistenceFactory events, TenantBusinessTraceabilityBindingsFactory traceability,
                WarehouseObjectAccess warehouses, OperationalSettingsAccess settings) {
            var runner = new TenantBoundWarehouseOperationsRequestRunner(router, compositions, accounts, skus,
                    orders, fulfillment, evidence, events, traceability, warehouses, settings);
            return (c, source, destination, page, size) -> runner.read(c,
                    service -> service.transfers(c, source, destination, page, size));
        }

        @Bean
        WarehouseOperationsRequestRunner.Receiving cloudWarehouseReceiving(TenantBusinessDatabaseRouter router,
                TenantWarehouseOperationsCompositionFactory compositions, TenantCustomerAccountQueryFactory accounts,
                TenantSellableSkuQueryFactory skus, TenantSalesOrderFulfillmentQueryFactory orders,
                TenantFulfillmentInventoryQueryFactory fulfillment, TenantBusinessEvidenceQueryFactory evidence,
                TenantChangeEventPersistenceFactory events, TenantBusinessTraceabilityBindingsFactory traceability,
                WarehouseObjectAccess warehouses, OperationalSettingsAccess settings) {
            var runner = new TenantBoundWarehouseOperationsRequestRunner(router, compositions, accounts, skus,
                    orders, fulfillment, evidence, events, traceability, warehouses, settings);
            return new WarehouseOperationsRequestRunner.Receiving() {
                @Override
                public WarehouseOperationsService.Page<WarehouseOperationsService.ZoneSummary> zones(
                        CurrentAccessContext context, String warehouseId, int page, int size) {
                    return runner.read(context, service -> service.zones(context, warehouseId, page, size));
                }

                @Override
                public WarehouseOperationsService.LotSummary receive(CurrentAccessContext context,
                        WarehouseOperationsService.Receipt receipt, String key, String correlation) {
                    return runner.execute(context, service -> service.receive(context, receipt, key, correlation));
                }
            };
        }

        @Bean
        @Primary
        PickingWorkRead cloudPickingWorkRead(TenantBusinessDatabaseRouter router,
                TenantFulfillmentDeliveryCompositionProviderAdapter compositions,
                WarehouseObjectAccess warehouses, WorkforceDirectory workforce) {
            var runner = new TenantBoundFulfillmentDeliveryRequestRunner(router, compositions, warehouses, workforce);
            return new PickingWorkRead() {
                public FulfillmentWorkListModels.Page list(
                        CurrentAccessContext c, int page, int size) { return runner.listPickingWork(c, page, size); }
                public FulfillmentModels.FulfillmentView fulfillment(
                        CurrentAccessContext c, UUID id) { return runner.readFulfillment(c, id); }
                public PhysicalAllocationModels.PhysicalAllocationView allocation(
                        CurrentAccessContext c, UUID id) { return runner.readAllocation(c, id); }
            };
        }
    }
}
