package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityBindingsPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory.Bindings;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantColdChainPolicyQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryBackingQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryTemperatureHoldCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseEventContextQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseLogisticsFulfillmentFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseSelectionQueryFactory;
import com.nexa.api.payments.tenantdatabase.TenantPaymentConfirmationQueryFactory;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentCommandsFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Binds owner technical factories on the exact callback session, then asks BC-06 to assemble its services. */
@Component
@Profile("!test")
public final class TenantFulfillmentDeliveryCompositionProviderAdapter {
    private final TenantFulfillmentDeliveryCompositionFactory compositions;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantSellableSkuQueryFactory sellableSkus;
    private final TenantSalesOrderFulfillmentQueryFactory salesOrderQueries;
    private final TenantSalesOrderFulfillmentCommandsFactory salesOrderCommands;
    private final TenantPhysicalAllocationCommandsFactory physicalAllocations;
    private final TenantInventoryBackingQueryFactory inventoryBackings;
    private final TenantColdChainPolicyQueryFactory coldChainPolicies;
    private final TenantInventoryTemperatureHoldCommandsFactory temperatureHolds;
    private final TenantWarehouseSelectionQueryFactory warehouseSelections;
    private final TenantWarehouseLogisticsFulfillmentFactory warehouseLogistics;
    private final TenantWarehouseEventContextQueryFactory warehouseEvents;
    private final TenantBusinessEvidenceQueryFactory businessEvidence;
    private final TenantChangeEventPersistenceFactory changeEvents;
    private final TenantCreditAccountAdapterFactory creditAccounts;
    private final TenantPaymentConfirmationQueryFactory paymentConfirmations;
    private final TenantFulfillmentInventoryQueryFactory fulfillmentQueries;
    private final TenantBusinessTraceabilityBindingsPort traceabilityBindings;

    public TenantFulfillmentDeliveryCompositionProviderAdapter(
            TenantFulfillmentDeliveryCompositionFactory compositions,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantSellableSkuQueryFactory sellableSkus,
            TenantSalesOrderFulfillmentQueryFactory salesOrderQueries,
            TenantSalesOrderFulfillmentCommandsFactory salesOrderCommands,
            TenantPhysicalAllocationCommandsFactory physicalAllocations,
            TenantInventoryBackingQueryFactory inventoryBackings,
            TenantColdChainPolicyQueryFactory coldChainPolicies,
            TenantInventoryTemperatureHoldCommandsFactory temperatureHolds,
            TenantWarehouseSelectionQueryFactory warehouseSelections,
            TenantWarehouseLogisticsFulfillmentFactory warehouseLogistics,
            TenantWarehouseEventContextQueryFactory warehouseEvents,
            TenantBusinessEvidenceQueryFactory businessEvidence,
            TenantChangeEventPersistenceFactory changeEvents,
            TenantCreditAccountAdapterFactory creditAccounts,
            TenantPaymentConfirmationQueryFactory paymentConfirmations,
            TenantFulfillmentInventoryQueryFactory fulfillmentQueries,
            TenantBusinessTraceabilityBindingsPort traceabilityBindings) {
        this.compositions = Objects.requireNonNull(compositions);
        this.customerAccounts = Objects.requireNonNull(customerAccounts);
        this.sellableSkus = Objects.requireNonNull(sellableSkus);
        this.salesOrderQueries = Objects.requireNonNull(salesOrderQueries);
        this.salesOrderCommands = Objects.requireNonNull(salesOrderCommands);
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations);
        this.inventoryBackings = Objects.requireNonNull(inventoryBackings);
        this.coldChainPolicies = Objects.requireNonNull(coldChainPolicies);
        this.temperatureHolds = Objects.requireNonNull(temperatureHolds);
        this.warehouseSelections = Objects.requireNonNull(warehouseSelections);
        this.warehouseLogistics = Objects.requireNonNull(warehouseLogistics);
        this.warehouseEvents = Objects.requireNonNull(warehouseEvents);
        this.businessEvidence = Objects.requireNonNull(businessEvidence);
        this.changeEvents = Objects.requireNonNull(changeEvents);
        this.creditAccounts = Objects.requireNonNull(creditAccounts);
        this.paymentConfirmations = Objects.requireNonNull(paymentConfirmations);
        this.fulfillmentQueries = Objects.requireNonNull(fulfillmentQueries);
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings);
    }

    public FulfillmentDeliveryComposition bindTo(JdbcTemplate tenantJdbc,
                                                 WarehouseObjectAccess verifiedWarehouseAccess,
                                                 WorkforceDirectory verifiedWorkforce) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        WarehouseObjectAccess access = Objects.requireNonNull(verifiedWarehouseAccess,
                "Preflight Warehouse access is required");
        WorkforceDirectory workforce = Objects.requireNonNull(verifiedWorkforce,
                "Preflight workforce snapshot is required");

        CustomerAccountQuery accounts = Objects.requireNonNull(customerAccounts.bindTo(jdbc),
                "Tenant Customer Account factory returned no query");
        SellableSkuQuery skus = Objects.requireNonNull(sellableSkus.bindTo(jdbc, catalogAccounts(accounts)),
                "Tenant Catalog factory returned no Sellable SKU query");
        SalesOrderFulfillmentQuery orders = Objects.requireNonNull(salesOrderQueries.bindTo(jdbc),
                "Tenant Sales factory returned no order query");
        var orderCommands = Objects.requireNonNull(salesOrderCommands.bindTo(jdbc),
                "Tenant Sales factory returned no order commands");
        FulfillmentInventoryQuery fulfillmentInventory = Objects.requireNonNull(fulfillmentQueries.bindTo(jdbc),
                "Tenant fulfillment inventory factory returned no query");
        ChangeEventPersistencePort changeFeed = Objects.requireNonNull(changeEvents.bindTo(jdbc),
                "Tenant change-feed factory returned no adapter");
        var traceability = Objects.requireNonNull(traceabilityBindings.bindTo(jdbc),
                "Tenant traceability factory returned no bindings");
        BusinessEvidenceQuery evidence = Objects.requireNonNull(businessEvidence.bindTo(jdbc),
                "Tenant Business Evidence factory returned no query");

        InventoryCommercialSource commercialSource = commercialSource(orders);
        InventoryFulfillmentSource fulfillmentSource = fulfillmentSource(fulfillmentInventory);
        var allocations = Objects.requireNonNull(physicalAllocations.bindTo(jdbc, traceability.commands(),
                commercialSource, fulfillmentSource, skus, traceability.canonicalOutbox(), access),
                "Tenant physical allocation factory returned no commands");
        var backings = Objects.requireNonNull(inventoryBackings.bindTo(jdbc),
                "Tenant inventory backing factory returned no query");
        var coldChain = Objects.requireNonNull(coldChainPolicies.bindTo(jdbc, fulfillmentSource, skus),
                "Tenant cold-chain factory returned no query");
        var holdCommands = Objects.requireNonNull(temperatureHolds.bindTo(jdbc, changeFeed, skus,
                commercialSource, fulfillmentSource, access, evidence),
                "Tenant temperature hold factory returned no commands");
        var warehouseSelection = Objects.requireNonNull(warehouseSelections.bindTo(jdbc),
                "Tenant warehouse selection factory returned no query");
        var warehouseHandoff = Objects.requireNonNull(warehouseLogistics.bindTo(jdbc, changeFeed, commercialSource),
                "Tenant Warehouse logistics factory returned no port");
        var warehouseEventContext = Objects.requireNonNull(warehouseEvents.bindTo(jdbc),
                "Tenant Warehouse event-context factory returned no query");

        ReceivablePaymentAccess receivableAccess = Objects.requireNonNull(
                creditAccounts.bindReceivablePaymentAccessTo(jdbc),
                "Tenant Credit factory returned no receivable access");
        var paymentStatus = Objects.requireNonNull(paymentConfirmations.bindTo(jdbc, receivableAccess),
                "Tenant Payments factory returned no confirmation query");
        FinancialAdjustmentSource adjustmentSource = financialAdjustmentSource(orders, paymentStatus);
        var credit = Objects.requireNonNull(creditAccounts.bindTo(jdbc, accounts,
                TenantCreditEligibilitySourceFactory.bindTo(accounts), receivableAccess,
                traceability.commands(), traceability.canonicalOutbox(), adjustmentSource),
                "Tenant Credit factory returned no bindings");

        Bindings bindings = new Bindings(accounts, orders, orderCommands, allocations, backings,
                coldChain, holdCommands, warehouseSelection, warehouseHandoff, warehouseEventContext, evidence,
                changeFeed, credit.financialAdjustments(), traceability.commands(), traceability.canonicalOutbox());
        return Objects.requireNonNull(compositions.bindTo(jdbc, access, workforce, bindings),
                "BC-06 composition factory returned no composition");
    }

    private static InventoryCommercialSource commercialSource(SalesOrderFulfillmentQuery orders) {
        return new InventoryCommercialSource() {
            @Override
            public Optional<Snapshot> findCandidate(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return lookup(tenantId, workspaceId, salesOrderId, false);
            }

            @Override
            public Optional<Snapshot> claimCandidate(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return lookup(tenantId, workspaceId, salesOrderId, true);
            }

            private Optional<Snapshot> lookup(UUID tenantId, UUID workspaceId, UUID salesOrderId, boolean claim) {
                try {
                    SalesOrderFulfillmentQuery.Snapshot order = claim
                            ? orders.getForUpdate(tenantId, workspaceId, salesOrderId)
                            : orders.get(tenantId, workspaceId, salesOrderId);
                    return Optional.of(new Snapshot(order.id(), order.number(), order.status(), order.version(),
                            order.clientAccountId(), order.commercialCommitmentId(), order.destinationSnapshot(),
                            order.lines().stream().map(line -> new Line(line.id(), line.skuId(),
                                    line.catalogItemId(), line.quantity(), line.unit())).toList()));
                } catch (CommercialBusinessException exception) {
                    if (!"SALES_ORDER_NOT_FOUND".equals(exception.code())) throw exception;
                    return Optional.empty();
                }
            }
        };
    }

    private static InventoryFulfillmentSource fulfillmentSource(FulfillmentInventoryQuery query) {
        return new InventoryFulfillmentSource() {
            @Override
            public boolean hasFulfillment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return query.hasFulfillment(tenantId, workspaceId, salesOrderId);
            }

            @Override
            public boolean hasActiveDispatch(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return query.hasActiveDispatch(tenantId, workspaceId, salesOrderId);
            }

            @Override
            public Optional<UUID> physicalAllocationForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
                return query.physicalAllocationForDelivery(tenantId, workspaceId, deliveryId);
            }
        };
    }

    private static FinancialAdjustmentSource financialAdjustmentSource(SalesOrderFulfillmentQuery orders,
                                                                        com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery payments) {
        return new FinancialAdjustmentSource() {
            @Override
            public Snapshot claimSalesOrderCorrection(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                SalesOrderFulfillmentQuery.Snapshot order = orders.getForUpdate(tenantId, workspaceId, salesOrderId);
                return new Snapshot(order.status(), order.currency(), order.total());
            }

            @Override
            public boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return payments.hasSuccessfulPayment(tenantId, workspaceId, salesOrderId);
            }
        };
    }

    private static CatalogClientAccountPort catalogAccounts(CustomerAccountQuery accounts) {
        return new CatalogClientAccountPort() {
            @Override
            public Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
                return accounts.findUnfilteredReferenceForMembership(tenantId.toString(), workspaceId.toString(),
                        membershipId.toString()).map(UUID::fromString);
            }

            @Override
            public Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId,
                                                                           UUID membershipId) {
                return accounts.findActiveBuyerDetails(tenantId.toString(), workspaceId.toString(),
                        membershipId.toString()).map(account -> profile(UUID.fromString(account.id()),
                        account.segment()));
            }

            @Override
            public Optional<ClientAccountProfile> findActiveProfile(UUID tenantId, UUID workspaceId,
                                                                     UUID customerAccountId) {
                return accounts.findActiveDetails(tenantId.toString(), workspaceId.toString(),
                        customerAccountId.toString()).map(account -> profile(UUID.fromString(account.id()),
                        account.segment()));
            }
        };
    }

    private static CatalogClientAccountPort.ClientAccountProfile profile(UUID id, String segment) {
        return new CatalogClientAccountPort.ClientAccountProfile(id, segment, null);
    }
}
