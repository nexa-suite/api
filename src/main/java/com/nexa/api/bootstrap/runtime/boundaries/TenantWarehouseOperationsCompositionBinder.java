package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Binds the existing BC-05 owner composition from immutable central authority snapshots. */
@Component
public final class TenantWarehouseOperationsCompositionBinder {
    private final TenantWarehouseOperationsCompositionFactory compositions;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantSellableSkuQueryFactory sellableSkus;
    private final TenantSalesOrderFulfillmentQueryFactory salesOrders;
    private final TenantFulfillmentInventoryQueryFactory fulfillmentQueries;
    private final TenantBusinessEvidenceQueryFactory businessEvidence;
    private final TenantChangeEventPersistenceFactory changeEvents;
    private final TenantBusinessTraceabilityBindingsFactory traceability;

    public TenantWarehouseOperationsCompositionBinder(TenantWarehouseOperationsCompositionFactory compositions,
            TenantCustomerAccountQueryFactory customerAccounts, TenantSellableSkuQueryFactory sellableSkus,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantFulfillmentInventoryQueryFactory fulfillmentQueries,
            TenantBusinessEvidenceQueryFactory businessEvidence, TenantChangeEventPersistenceFactory changeEvents,
            TenantBusinessTraceabilityBindingsFactory traceability) {
        this.compositions = Objects.requireNonNull(compositions);
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.sellableSkus = Objects.requireNonNull(sellableSkus);
        this.salesOrders = Objects.requireNonNull(salesOrders);
        this.fulfillmentQueries = Objects.requireNonNull(fulfillmentQueries);
        this.businessEvidence = Objects.requireNonNull(businessEvidence);
        this.changeEvents = Objects.requireNonNull(changeEvents);
        this.traceability = Objects.requireNonNull(traceability);
    }

    public WarehouseOperationsService bindTo(JdbcTemplate tenantJdbc, CurrentAccessContext verifiedContext,
            Set<UUID> activeWarehouseIds, Optional<OperationalSettingsAccess.Snapshot> centralSettings) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        CurrentAccessContext context = Objects.requireNonNull(verifiedContext, "Verified workflow actor is required");
        Set<UUID> warehouses = Set.copyOf(Objects.requireNonNull(activeWarehouseIds,
                "Central Warehouse access snapshot is required"));
        Optional<OperationalSettingsAccess.Snapshot> settings = Objects.requireNonNull(centralSettings,
                "Central operational-settings snapshot is required");

        CustomerAccountQuery tenantAccounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc));
        SellableSkuQuery tenantSkus = Objects.requireNonNull(sellableSkus.bindTo(jdbc,
                TenantWarehouseRequestBindings.catalogAccounts(tenantAccounts)));
        SalesOrderFulfillmentQuery tenantOrders = Objects.requireNonNull(salesOrders.bindTo(jdbc));
        FulfillmentInventoryQuery tenantFulfillment = Objects.requireNonNull(fulfillmentQueries.bindTo(jdbc));
        ChangeEventPersistencePort tenantChangeFeed = Objects.requireNonNull(changeEvents.bindTo(jdbc));
        var traceBindings = traceability.bindTo(jdbc);
        BusinessEvidenceQuery tenantEvidence = Objects.requireNonNull(businessEvidence.bindTo(jdbc));
        InventoryCommercialSource commercial = TenantWarehouseRequestBindings.commercialSource(tenantOrders);
        InventoryFulfillmentSource fulfillment = TenantWarehouseRequestBindings.fulfillmentSource(tenantFulfillment);
        WarehouseObjectAccess tenantWarehouseAccess = TenantWarehouseRequestBindings.warehouseAccessSnapshot(context,
                warehouses);
        Optional<WarehouseOperationalSettingsPort.Snapshot> tenantSettings = settings.map(value ->
                new WarehouseOperationalSettingsPort.Snapshot(value.selectionPolicy(), value.orderCutoffPolicy(),
                        value.fulfillmentDefaults(), value.inventoryVisibilityPolicy(), value.buyerAvailabilityPolicy(),
                        value.startsAt(), value.endsAt(), value.orderCutoffMinutes(), value.thermalLogRequired(),
                        value.version()));
        return Objects.requireNonNull(compositions.bindTo(jdbc,
                new com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory.Bindings(
                        tenantWarehouseAccess, tenantSettings, tenantSkus, commercial, fulfillment, tenantEvidence,
                        tenantChangeFeed, traceBindings.canonicalOutbox())),
                "Tenant Warehouse composition factory returned no service");
    }
}
