package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationsRequestRunner;
import com.nexa.api.inventoryavailability.application.port.WarehouseSelectionRequestRunner;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationsStoreUnavailableException;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseSelectionQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.function.Function;

/** Selects only the explicit Tenant Warehouse route; disabled requests fail closed. */
@Configuration(proxyBeanMethods = false)
public class TenantBoundWarehouseOperationsConfiguration {
    @Bean
    @Primary
    @Profile("local")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.warehouse-operations", name = "enabled",
            havingValue = "true", matchIfMissing = false)
    WarehouseOperationsRequestRunner tenantBoundWarehouseOperationsRequestRunner(
            TenantBusinessDatabaseRouter router,
            TenantWarehouseOperationsCompositionFactory compositions,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantSellableSkuQueryFactory sellableSkus,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantFulfillmentInventoryQueryFactory fulfillmentQueries,
            TenantBusinessEvidenceQueryFactory businessEvidence,
            TenantChangeEventPersistenceFactory changeEvents,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings,
            WarehouseObjectAccess warehouseAccess,
            OperationalSettingsAccess operationalSettings) {
        return new TenantBoundWarehouseOperationsRequestRunner(router, compositions, customerAccounts,
                sellableSkus, salesOrders, fulfillmentQueries, businessEvidence, changeEvents,
                traceabilityBindings, warehouseAccess, operationalSettings);
    }

    @Bean
    @Primary
    @Profile("local")
    @ConditionalOnProperty(prefix = "nexa.tenant-business.warehouse-operations", name = "enabled",
            havingValue = "true", matchIfMissing = false)
    WarehouseSelectionRequestRunner tenantBoundWarehouseSelectionRequestRunner(
            TenantBusinessDatabaseRouter router, TenantWarehouseSelectionQueryFactory queries) {
        return new TenantBoundWarehouseSelectionRequestRunner(router, queries);
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(WarehouseOperationsRequestRunner.class)
    WarehouseOperationsRequestRunner unavailableWarehouseOperationsRequestRunner() {
        return new WarehouseOperationsRequestRunner() {
            @Override
            public <T> T execute(CurrentAccessContext context, Function<WarehouseOperationsService, T> operation) {
                throw new WarehouseOperationsStoreUnavailableException();
            }
        };
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(WarehouseSelectionRequestRunner.class)
    WarehouseSelectionRequestRunner unavailableWarehouseSelectionRequestRunner() {
        return (context, warehouseId) -> {
            throw new WarehouseOperationsStoreUnavailableException();
        };
    }
}
