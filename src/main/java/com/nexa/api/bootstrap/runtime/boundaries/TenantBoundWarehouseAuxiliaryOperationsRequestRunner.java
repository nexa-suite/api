package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.port.WarehouseAuxiliaryOperationsRequestRunner;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.LotIdentifierResolutionQuery;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationSubstitutionRequests;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationsStoreUnavailableException;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInboundReceivingDiscrepancyCasesFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantLotIdentifierResolutionQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationSubstitutionRequestsFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Composes auxiliary BC-05 use cases on the same routed Tenant transaction as the main Warehouse API. */
public final class TenantBoundWarehouseAuxiliaryOperationsRequestRunner
        implements WarehouseAuxiliaryOperationsRequestRunner {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantSellableSkuQueryFactory sellableSkus;
    private final TenantSalesOrderFulfillmentQueryFactory salesOrders;
    private final TenantFulfillmentInventoryQueryFactory fulfillmentQueries;
    private final TenantBusinessEvidenceQueryFactory businessEvidence;
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;
    private final TenantInboundReceivingDiscrepancyCasesFactory discrepancyCases;
    private final TenantPhysicalAllocationCommandsFactory allocations;
    private final TenantPhysicalAllocationSubstitutionRequestsFactory substitutions;
    private final TenantLotIdentifierResolutionQueryFactory lotIdentifiers;
    private final WarehouseObjectAccess centralWarehouseAccess;
    private final Clock clock;

    public TenantBoundWarehouseAuxiliaryOperationsRequestRunner(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantSellableSkuQueryFactory sellableSkus,
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantFulfillmentInventoryQueryFactory fulfillmentQueries,
            TenantBusinessEvidenceQueryFactory businessEvidence,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings,
            TenantInboundReceivingDiscrepancyCasesFactory discrepancyCases,
            TenantPhysicalAllocationCommandsFactory allocations,
            TenantPhysicalAllocationSubstitutionRequestsFactory substitutions,
            TenantLotIdentifierResolutionQueryFactory lotIdentifiers,
            WarehouseObjectAccess centralWarehouseAccess, Clock clock) {
        this.router = Objects.requireNonNull(router);
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.sellableSkus = Objects.requireNonNull(sellableSkus);
        this.salesOrders = Objects.requireNonNull(salesOrders);
        this.fulfillmentQueries = Objects.requireNonNull(fulfillmentQueries);
        this.businessEvidence = Objects.requireNonNull(businessEvidence);
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings);
        this.discrepancyCases = Objects.requireNonNull(discrepancyCases);
        this.allocations = Objects.requireNonNull(allocations);
        this.substitutions = Objects.requireNonNull(substitutions);
        this.lotIdentifiers = Objects.requireNonNull(lotIdentifiers);
        this.centralWarehouseAccess = Objects.requireNonNull(centralWarehouseAccess);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public <T> T execute(CurrentAccessContext context, Function<Operations, T> operation) {
        CurrentAccessContext verified = Objects.requireNonNull(context, "Verified access context is required");
        Function<Operations, T> request = Objects.requireNonNull(operation, "Warehouse operation is required");
        Set<UUID> allowedWarehouses = Set.copyOf(centralWarehouseAccess.activeWarehouseIds(verified));
        WarehouseObjectAccess access = TenantWarehouseRequestBindings.warehouseAccessSnapshot(
                verified, allowedWarehouses);
        try {
            return router.inTransaction(verified, jdbc -> request.apply(bind(jdbc, access)));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | CannotGetJdbcConnectionException | CannotCreateTransactionException unavailable) {
            throw new WarehouseOperationsStoreUnavailableException(unavailable);
        }
    }

    private Operations bind(JdbcTemplate jdbc, WarehouseObjectAccess access) {
        CustomerAccountQuery accounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc),
                "Tenant Customer Account factory returned no query");
        CatalogClientAccountPort catalogAccounts = TenantWarehouseRequestBindings.catalogAccounts(accounts);
        SellableSkuQuery skus = Objects.requireNonNull(sellableSkus.bindTo(jdbc, catalogAccounts),
                "Tenant Catalog factory returned no SKU query");
        SalesOrderFulfillmentQuery orders = Objects.requireNonNull(salesOrders.bindTo(jdbc),
                "Tenant Sales factory returned no query");
        FulfillmentInventoryQuery fulfillmentQuery = Objects.requireNonNull(fulfillmentQueries.bindTo(jdbc),
                "Tenant Fulfillment factory returned no query");
        InventoryCommercialSource commercial = TenantWarehouseRequestBindings.commercialSource(orders);
        InventoryFulfillmentSource fulfillment = TenantWarehouseRequestBindings.fulfillmentSource(fulfillmentQuery);
        var traceability = traceabilityBindings.bindTo(jdbc);
        CanonicalOutboxPort outbox = traceability.canonicalOutbox();
        BusinessEvidenceQuery evidence = Objects.requireNonNull(businessEvidence.bindTo(jdbc),
                "Tenant Business Evidence factory returned no query");
        InboundReceivingDiscrepancyCases cases = Objects.requireNonNull(
                discrepancyCases.bindTo(jdbc, access, skus), "Tenant discrepancy factory returned no cases");
        PhysicalAllocationCommands physicalAllocations = Objects.requireNonNull(allocations.bindTo(jdbc,
                traceability.commands(), commercial, fulfillment, skus, outbox, access),
                "Tenant physical allocation factory returned no commands");
        PhysicalAllocationSubstitutionRequests substitutionRequests = Objects.requireNonNull(
                substitutions.bindTo(jdbc, traceability.commands(), commercial, fulfillment, skus, outbox, access),
                "Tenant substitution factory returned no requests");
        LotIdentifierResolutionQuery lotQuery = Objects.requireNonNull(lotIdentifiers.bindTo(jdbc),
                "Tenant lot identifier factory returned no query");
        return new Operations(
                new com.nexa.api.inventoryavailability.application.service.InboundReceivingDiscrepancyService(
                        cases, evidence, clock),
                new com.nexa.api.inventoryavailability.application.service.PhysicalAllocationSubstitutionService(
                        substitutionRequests, clock),
                new com.nexa.api.inventoryavailability.application.service.PhysicalScanValidationService(
                        physicalAllocations, clock, access),
                new com.nexa.api.inventoryavailability.application.service.LotIdentifierResolutionService(
                        lotQuery, access));
    }
}
