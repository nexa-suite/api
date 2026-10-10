package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessTraceabilityBindingsFactory;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentSource;
import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.service.BuyerDeliveryTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchReadinessService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentDriverAssignmentService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentWorkListService;
import com.nexa.api.fulfillmentdelivery.application.service.OutgoingGoodsCheckService;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.DispatchCommandPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.DispatchQueryPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.DispatchRouteStartPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcBuyerDeliveryTrackingQueryAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDeliveryOutcomeAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDispatchReadinessQueryAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDriverDeliveryPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDriverTrackingAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcFulfillmentLifecycleAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcOperationalExceptionPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcOutgoingGoodsCheckAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.OperationalHandoffPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryTemperatureHoldCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseEventContextQueryPort;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantColdChainPolicyQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryBackingQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantInventoryTemperatureHoldCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseEventContextQueryFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseLogisticsFulfillmentFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseSelectionQueryFactory;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.payments.tenantdatabase.TenantPaymentConfirmationQueryFactory;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentCommandsFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;

/** Composes BC-06's existing rules and adapters on one Tenant transaction session. */
@Component
@Profile("!test")
public final class JdbcTenantFulfillmentDeliveryCompositionFactory
        implements TenantFulfillmentDeliveryCompositionFactory {
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
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;
    private final Clock clock;
    private final ObjectMapper mapper;

    public JdbcTenantFulfillmentDeliveryCompositionFactory(
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
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings,
            Clock clock, ObjectMapper mapper) {
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
        this.clock = Objects.requireNonNull(clock);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
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
        SalesOrderFulfillmentCommands orderCommands = Objects.requireNonNull(salesOrderCommands.bindTo(jdbc),
                "Tenant Sales factory returned no order commands");

        InventoryCommercialSource commercialSource = commercialSource(orders);
        FulfillmentInventoryQuery tenantFulfillmentQuery = Objects.requireNonNull(fulfillmentQueries.bindTo(jdbc),
                "Tenant fulfillment inventory factory returned no query");
        InventoryFulfillmentSource fulfillmentSource = fulfillmentSource(tenantFulfillmentQuery);
        ChangeEventPersistencePort changeFeed = Objects.requireNonNull(changeEvents.bindTo(jdbc),
                "Tenant change-feed factory returned no adapter");
        var traceability = traceabilityBindings.bindTo(jdbc);
        BusinessEvidenceQuery evidence = Objects.requireNonNull(businessEvidence.bindTo(jdbc),
                "Tenant Business Evidence factory returned no query");
        PhysicalAllocationCommands allocations = Objects.requireNonNull(physicalAllocations.bindTo(jdbc,
                traceability.commands(), commercialSource, fulfillmentSource, skus,
                traceability.canonicalOutbox(), access), "Tenant physical allocation factory returned no commands");
        InventoryBackingQuery backings = Objects.requireNonNull(inventoryBackings.bindTo(jdbc),
                "Tenant inventory backing factory returned no query");
        ColdChainPolicyQuery coldChain = Objects.requireNonNull(
                coldChainPolicies.bindTo(jdbc, fulfillmentSource, skus),
                "Tenant cold-chain factory returned no query");
        InventoryTemperatureHoldCommands holdCommands = Objects.requireNonNull(temperatureHolds.bindTo(jdbc,
                changeFeed, skus, commercialSource, fulfillmentSource, access, evidence),
                "Tenant temperature hold factory returned no commands");
        WarehouseSelectionQuery warehouseSelection = Objects.requireNonNull(warehouseSelections.bindTo(jdbc),
                "Tenant warehouse selection factory returned no query");
        WarehouseLogisticsFulfillmentPort warehouseHandoff = Objects.requireNonNull(
                warehouseLogistics.bindTo(jdbc, changeFeed, commercialSource),
                "Tenant Warehouse logistics factory returned no port");
        WarehouseEventContextQueryPort warehouseEventContext = Objects.requireNonNull(
                warehouseEvents.bindTo(jdbc), "Tenant Warehouse event-context factory returned no query");

        ReceivablePaymentAccess receivableAccess = Objects.requireNonNull(
                creditAccounts.bindReceivablePaymentAccessTo(jdbc),
                "Tenant Credit factory returned no receivable access");
        PaymentConfirmationQuery paymentStatus = Objects.requireNonNull(
                paymentConfirmations.bindTo(jdbc, receivableAccess),
                "Tenant Payments factory returned no confirmation query");
        FinancialAdjustmentSource adjustmentSource = financialAdjustmentSource(orders, paymentStatus);
        var credit = creditAccounts.bindTo(jdbc, accounts, receivableAccess, traceability.commands(),
                traceability.canonicalOutbox(), adjustmentSource);

        JdbcDispatchReadinessQueryAdapter readinessQuery = new JdbcDispatchReadinessQueryAdapter(jdbc);
        DispatchReadinessService readiness = new DispatchReadinessService(readinessQuery, allocations, access, clock);
        JdbcOutgoingGoodsCheckAdapter outgoingPersistence = new JdbcOutgoingGoodsCheckAdapter(jdbc);
        OutgoingGoodsCheckService outgoing = new OutgoingGoodsCheckService(readiness, allocations,
                outgoingPersistence, clock);
        JdbcFulfillmentLifecycleAdapter fulfillmentPersistence = new JdbcFulfillmentLifecycleAdapter(jdbc, clock,
                allocations, traceability.canonicalOutbox());
        FulfillmentLifecycleService lifecycle = new FulfillmentLifecycleService(orders, orderCommands, backings,
                allocations, fulfillmentPersistence, new JdbcDeliveryOutcomeAdapter(jdbc, coldChain, mapper, clock,
                traceability.canonicalOutbox()), credit.financialAdjustments(), evidence,
                traceability.commands(), coldChain, holdCommands, warehouseSelection, access, outgoing, clock);
        FulfillmentDriverAssignmentService driverAssignments = new FulfillmentDriverAssignmentService(readiness,
                allocations, workforce, fulfillmentPersistence, traceability.commands(), clock);
        FulfillmentWorkListService workList = new FulfillmentWorkListService(readinessQuery, allocations,
                access, clock);
        DriverDeliveryService driverDeliveries = new DriverDeliveryService(
                new JdbcDriverDeliveryPersistenceAdapter(jdbc, false), lifecycle, clock);
        DriverTrackingService driverTracking = new DriverTrackingService(new JdbcDriverTrackingAdapter(jdbc),
                workforce, accounts, orders, clock);
        BuyerDeliveryTrackingService buyerTracking = new BuyerDeliveryTrackingService(
                new JdbcBuyerDeliveryTrackingQueryAdapter(jdbc, orders), accounts, orders);

        var handoffNotifications = new ChangeFeedOperationalHandoffNotificationAdapter(changeFeed);
        var dispatchQueries = new DispatchQueryPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                orders, accounts, workforce);
        var dispatchCommands = new DispatchCommandPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                warehouseEventContext, handoffNotifications, orders, accounts, workforce,
                traceability.canonicalOutbox(), new JdbcOperationalExceptionPersistenceAdapter(jdbc));
        var routeStart = new DispatchRouteStartPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                orders, accounts);
        var handoff = new OperationalHandoffPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                handoffNotifications, orders, accounts);
        LogisticsOperationsService logistics = new LogisticsOperationsService(dispatchQueries, dispatchCommands,
                accounts, new com.nexa.api.fulfillmentdelivery.application.service.StartDispatchRouteService(
                routeStart, warehouseHandoff), handoff);

        return new FulfillmentDeliveryComposition(lifecycle, workList, outgoing, driverDeliveries,
                driverTracking, buyerTracking, logistics, readiness, driverAssignments);
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

    private static InventoryFulfillmentSource fulfillmentSource(
            com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery query) {
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
                                                                        PaymentConfirmationQuery payments) {
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
