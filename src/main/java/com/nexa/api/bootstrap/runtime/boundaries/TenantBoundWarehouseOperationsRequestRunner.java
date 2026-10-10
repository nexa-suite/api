package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
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
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationsRequestRunner;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationsStoreUnavailableException;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Central preflight plus one request-local Warehouse composition on the verified Tenant session. */
public final class TenantBoundWarehouseOperationsRequestRunner implements WarehouseOperationsRequestRunner {
    private final TenantBusinessDatabaseRouter router;
    private final TenantWarehouseOperationsCompositionFactory compositions;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantSellableSkuQueryFactory sellableSkus;
    private final TenantSalesOrderFulfillmentQueryFactory salesOrders;
    private final TenantFulfillmentInventoryQueryFactory fulfillmentQueries;
    private final TenantBusinessEvidenceQueryFactory businessEvidence;
    private final TenantChangeEventPersistenceFactory changeEvents;
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;
    private final WarehouseObjectAccess centralWarehouseAccess;
    private final OperationalSettingsAccess centralOperationalSettings;

    public TenantBoundWarehouseOperationsRequestRunner(TenantBusinessDatabaseRouter router,
            TenantWarehouseOperationsCompositionFactory compositions,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantSellableSkuQueryFactory sellableSkus,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantFulfillmentInventoryQueryFactory fulfillmentQueries,
            TenantBusinessEvidenceQueryFactory businessEvidence,
            TenantChangeEventPersistenceFactory changeEvents,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings,
            WarehouseObjectAccess centralWarehouseAccess,
            OperationalSettingsAccess centralOperationalSettings) {
        this.router = Objects.requireNonNull(router, "Tenant database router is required");
        this.compositions = Objects.requireNonNull(compositions, "Tenant Warehouse composition factory is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.sellableSkus = Objects.requireNonNull(sellableSkus);
        this.salesOrders = Objects.requireNonNull(salesOrders);
        this.fulfillmentQueries = Objects.requireNonNull(fulfillmentQueries);
        this.businessEvidence = Objects.requireNonNull(businessEvidence);
        this.changeEvents = Objects.requireNonNull(changeEvents);
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings);
        this.centralWarehouseAccess = Objects.requireNonNull(centralWarehouseAccess);
        this.centralOperationalSettings = Objects.requireNonNull(centralOperationalSettings);
    }

    @Override
    public <T> T execute(CurrentAccessContext context, Function<WarehouseOperationsService, T> operation) {
        CurrentAccessContext verified = Objects.requireNonNull(context, "Verified access context is required");
        Function<WarehouseOperationsService, T> request = Objects.requireNonNull(operation,
                "Warehouse request operation is required");

        // These owner facts are read before the router borrows a Tenant connection.
        Set<UUID> allowedWarehouses = Set.copyOf(centralWarehouseAccess.activeWarehouseIds(verified));
        Optional<OperationalSettingsAccess.Snapshot> settings = centralOperationalSettings
                .find(verified.workspaceId().toString());
        Optional<WarehouseOperationalSettingsPort.Snapshot> settingsSnapshot = settings.map(value ->
                new WarehouseOperationalSettingsPort.Snapshot(value.selectionPolicy(), value.orderCutoffPolicy(),
                        value.fulfillmentDefaults(), value.inventoryVisibilityPolicy(), value.buyerAvailabilityPolicy(),
                        value.startsAt(), value.endsAt(), value.orderCutoffMinutes(), value.thermalLogRequired(),
                        value.version()));

        WarehouseObjectAccess verifiedWarehouseAccess = TenantWarehouseRequestBindings.warehouseAccessSnapshot(
                verified, allowedWarehouses);
        try {
            return router.inTransaction(verified, tenantJdbc -> executeWithinTenant(verified, tenantJdbc,
                    verifiedWarehouseAccess, settingsSnapshot, request));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | CannotGetJdbcConnectionException | CannotCreateTransactionException unavailable) {
            throw new WarehouseOperationsStoreUnavailableException(unavailable);
        }
    }

    private <T> T executeWithinTenant(CurrentAccessContext context, JdbcTemplate jdbc,
            WarehouseObjectAccess access, Optional<WarehouseOperationalSettingsPort.Snapshot> settings,
            Function<WarehouseOperationsService, T> operation) {
        CustomerAccountQuery tenantAccounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc),
                "Tenant Customer Account factory returned no query");
        SellableSkuQuery tenantSkus = Objects.requireNonNull(sellableSkus.bindTo(jdbc,
                        TenantWarehouseRequestBindings.catalogAccounts(tenantAccounts)),
                "Tenant Catalog factory returned no SKU query");
        SalesOrderFulfillmentQuery tenantOrders = Objects.requireNonNull(salesOrders.bindTo(jdbc),
                "Tenant Sales factory returned no order query");
        FulfillmentInventoryQuery tenantFulfillment = Objects.requireNonNull(fulfillmentQueries.bindTo(jdbc),
                "Tenant Fulfillment factory returned no query");
        ChangeEventPersistencePort tenantChangeFeed = Objects.requireNonNull(changeEvents.bindTo(jdbc),
                "Tenant change-feed factory returned no adapter");
        var traceability = traceabilityBindings.bindTo(jdbc);
        BusinessEvidenceQuery tenantEvidence = Objects.requireNonNull(businessEvidence.bindTo(jdbc),
                "Tenant Business Evidence factory returned no query");

        InventoryCommercialSource commercial = TenantWarehouseRequestBindings.commercialSource(tenantOrders);
        InventoryFulfillmentSource fulfillment = TenantWarehouseRequestBindings.fulfillmentSource(tenantFulfillment);
        var bindings = new TenantWarehouseOperationsCompositionFactory.Bindings(access, settings, tenantSkus,
                commercial, fulfillment, tenantEvidence, tenantChangeFeed, traceability.canonicalOutbox());
        WarehouseOperationsService service = Objects.requireNonNull(compositions.bindTo(jdbc, bindings),
                "Tenant Warehouse factory returned no service");
        return operation.apply(service);
    }
}
