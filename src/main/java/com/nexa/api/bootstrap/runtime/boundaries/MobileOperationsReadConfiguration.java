package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.port.PickingWorkRead;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.port.InventoryLotsRead;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class MobileOperationsReadConfiguration {
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
