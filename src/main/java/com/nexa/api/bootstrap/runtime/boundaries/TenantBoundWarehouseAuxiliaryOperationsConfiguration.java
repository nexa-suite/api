package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.port.WarehouseAuxiliaryOperationsRequestRunner;
import com.nexa.api.inventoryavailability.application.port.WarehouseAuxiliaryOperationsRequestRunner.Operations;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationsStoreUnavailableException;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInboundReceivingDiscrepancyCasesFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantLotIdentifierResolutionQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationSubstitutionRequestsFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.util.function.Function;

/** Selects only the explicit Tenant route for auxiliary Warehouse HTTP operations. */
@Configuration(proxyBeanMethods = false)
public class TenantBoundWarehouseAuxiliaryOperationsConfiguration {
    @Bean
    @Primary
    @Profile("local")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.warehouse-operations", name = "enabled",
            havingValue = "true", matchIfMissing = false)
    WarehouseAuxiliaryOperationsRequestRunner tenantBoundWarehouseAuxiliaryOperationsRequestRunner(
            TenantBusinessDatabaseRouter router, TenantCustomerAccountQueryFactory customerAccounts,
            TenantSellableSkuQueryFactory sellableSkus,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantFulfillmentInventoryQueryFactory fulfillmentQueries,
            TenantBusinessEvidenceQueryFactory businessEvidence,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings,
            TenantInboundReceivingDiscrepancyCasesFactory discrepancyCases,
            TenantPhysicalAllocationCommandsFactory allocations,
            TenantPhysicalAllocationSubstitutionRequestsFactory substitutions,
            TenantLotIdentifierResolutionQueryFactory lotIdentifiers,
            WarehouseObjectAccess warehouseAccess, Clock clock) {
        return new TenantBoundWarehouseAuxiliaryOperationsRequestRunner(router, customerAccounts, sellableSkus,
                salesOrders, fulfillmentQueries, businessEvidence, traceabilityBindings, discrepancyCases,
                allocations, substitutions, lotIdentifiers, warehouseAccess, clock);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(WarehouseAuxiliaryOperationsRequestRunner.class)
    WarehouseAuxiliaryOperationsRequestRunner unavailableWarehouseAuxiliaryOperationsRequestRunner() {
        return new WarehouseAuxiliaryOperationsRequestRunner() {
            @Override
            public <T> T execute(CurrentAccessContext context, Function<Operations, T> operation) {
                throw new WarehouseOperationsStoreUnavailableException();
            }
        };
    }
}
