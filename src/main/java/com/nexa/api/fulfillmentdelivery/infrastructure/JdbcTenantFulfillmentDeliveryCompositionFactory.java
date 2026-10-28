package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DispatchWorkflowCommands;
import com.nexa.api.fulfillmentdelivery.application.service.MobileDeliveryContractService;
import com.nexa.api.fulfillmentdelivery.application.service.BuyerDeliveryTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchReadinessService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentDriverAssignmentService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentWorkListService;
import com.nexa.api.fulfillmentdelivery.application.service.OutgoingGoodsCheckService;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchWindowPlanService;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryLoadService;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryInstructionService;
import com.nexa.api.fulfillmentdelivery.application.service.ExecutionTemperatureService;
import com.nexa.api.fulfillmentdelivery.application.service.DriverDeliveryIncidentService;
import com.nexa.api.fulfillmentdelivery.application.service.OperationalExceptionService;
import com.nexa.api.fulfillmentdelivery.application.service.BomOperationalExceptionService;
import com.nexa.api.fulfillmentdelivery.application.service.CustomerInstructionService;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.DispatchCommandPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.DispatchQueryPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.DispatchRouteStartPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcBuyerDeliveryTrackingQueryAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDeliveryOutcomeAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDispatchReadinessQueryAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDriverDeliveryPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDriverTrackingAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDispatchWindowPlanPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDeliveryLoadPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDeliveryInstructionPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcExecutionTemperatureAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcDriverDeliveryIncidentPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcBomOperationalExceptionPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcCustomerInstructionAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcFulfillmentLifecycleAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcMobileDeliveryContractAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcOperationalExceptionPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.JdbcOutgoingGoodsCheckAdapter;
import com.nexa.api.fulfillmentdelivery.infrastructure.persistence.OperationalHandoffPersistenceAdapter;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory.Bindings;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Composes BC-06's existing rules and adapters on one Tenant transaction session. */
@Component
@Profile("!test")
public final class JdbcTenantFulfillmentDeliveryCompositionFactory
        implements TenantFulfillmentDeliveryCompositionFactory {
    private final Clock clock;
    private final ObjectMapper mapper;
    private final Duration handoffTtl;

    public JdbcTenantFulfillmentDeliveryCompositionFactory(Clock clock, ObjectMapper mapper,
                                                            @Value("${nexa.mobile.delivery-handoff-ttl:PT10M}")
                                                            Duration handoffTtl) {
        this.clock = Objects.requireNonNull(clock);
        this.mapper = Objects.requireNonNull(mapper);
        this.handoffTtl = Objects.requireNonNull(handoffTtl);
    }

    @Override
    public FulfillmentDeliveryComposition bindTo(JdbcTemplate tenantJdbc,
                                                 WarehouseObjectAccess verifiedWarehouseAccess,
                                                 WorkforceDirectory verifiedWorkforce,
                                                 Bindings dependencies) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        WarehouseObjectAccess access = Objects.requireNonNull(verifiedWarehouseAccess,
                "Preflight Warehouse access is required");
        WorkforceDirectory workforce = Objects.requireNonNull(verifiedWorkforce,
                "Preflight workforce snapshot is required");
        Bindings bindings = Objects.requireNonNull(dependencies, "Tenant business bindings are required");
        var accounts = bindings.customerAccounts();
        var orders = bindings.salesOrderQueries();
        var changeFeed = bindings.changeFeed();
        var warehouseHandoff = bindings.warehouseLogistics();
        var warehouseEventContext = bindings.warehouseEvents();

        JdbcDispatchReadinessQueryAdapter readinessQuery = new JdbcDispatchReadinessQueryAdapter(jdbc);
        DispatchReadinessService readiness = new DispatchReadinessService(readinessQuery,
                bindings.physicalAllocations(), access, clock);
        JdbcOutgoingGoodsCheckAdapter outgoingPersistence = new JdbcOutgoingGoodsCheckAdapter(jdbc);
        OutgoingGoodsCheckService outgoing = new OutgoingGoodsCheckService(readiness, bindings.physicalAllocations(),
                outgoingPersistence, clock);
        JdbcFulfillmentLifecycleAdapter fulfillmentPersistence = new JdbcFulfillmentLifecycleAdapter(jdbc, clock,
                bindings.physicalAllocations(), bindings.canonicalOutbox());
        FulfillmentLifecycleService lifecycle = new FulfillmentLifecycleService(orders, bindings.salesOrderCommands(),
                bindings.inventoryBackings(), bindings.physicalAllocations(), fulfillmentPersistence,
                new JdbcDeliveryOutcomeAdapter(jdbc, bindings.coldChainPolicies(), mapper, clock,
                        bindings.canonicalOutbox()), bindings.financialAdjustments(), bindings.businessEvidence(),
                bindings.traceabilityCommands(), bindings.coldChainPolicies(), bindings.temperatureHolds(),
                bindings.warehouseSelections(), access, outgoing, clock);
        FulfillmentDriverAssignmentService driverAssignments = new FulfillmentDriverAssignmentService(readiness,
                bindings.physicalAllocations(), workforce, fulfillmentPersistence,
                bindings.traceabilityCommands(), clock);
        FulfillmentWorkListService workList = new FulfillmentWorkListService(readinessQuery,
                bindings.physicalAllocations(),
                access, clock);
        DriverDeliveryService driverDeliveries = new DriverDeliveryService(
                new JdbcDriverDeliveryPersistenceAdapter(jdbc, false), lifecycle, clock);
        DriverTrackingService driverTracking = new DriverTrackingService(new JdbcDriverTrackingAdapter(jdbc),
                workforce, accounts, orders, clock);
        BuyerDeliveryTrackingService buyerTracking = new BuyerDeliveryTrackingService(
                new JdbcBuyerDeliveryTrackingQueryAdapter(jdbc, orders), accounts, orders);
        var operationalExceptions = new JdbcOperationalExceptionPersistenceAdapter(jdbc);
        DispatchWindowPlanService dispatchWindowPlans = new DispatchWindowPlanService(
                new JdbcDispatchWindowPlanPersistenceAdapter(jdbc), readiness, bindings.physicalAllocations(), clock);
        DeliveryLoadService deliveryLoads = new DeliveryLoadService(
                new JdbcDeliveryLoadPersistenceAdapter(jdbc, mapper), readiness, driverAssignments,
                bindings.physicalAllocations(), bindings.coldChainPolicies(), workforce, access, clock);
        DeliveryInstructionService deliveryInstructions = new DeliveryInstructionService(
                new JdbcDeliveryInstructionPersistenceAdapter(jdbc), readiness, clock);
        ExecutionTemperatureService executionTemperatures = new ExecutionTemperatureService(
                new JdbcExecutionTemperatureAdapter(jdbc), bindings.coldChainPolicies(),
                bindings.businessEvidence(), access, clock);
        DriverDeliveryIncidentService driverDeliveryIncidents = new DriverDeliveryIncidentService(
                new JdbcDriverDeliveryIncidentPersistenceAdapter(jdbc, operationalExceptions),
                bindings.businessEvidence(), bindings.traceabilityCommands(), clock);
        OperationalExceptionService exceptionOperations = new OperationalExceptionService(operationalExceptions, clock);
        BomOperationalExceptionService bomOperationalExceptions = new BomOperationalExceptionService(
                new JdbcBomOperationalExceptionPersistenceAdapter(jdbc), workforce, clock);
        CustomerInstructionService customerInstructions = new CustomerInstructionService(
                new JdbcCustomerInstructionAdapter(jdbc), orders, accounts, clock);

        var handoffNotifications = new ChangeFeedOperationalHandoffNotificationAdapter(changeFeed);
        var dispatchQueries = new DispatchQueryPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                orders, accounts, workforce);
        var dispatchCommands = new DispatchCommandPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                warehouseEventContext, handoffNotifications, orders, accounts, workforce,
                bindings.canonicalOutbox(), operationalExceptions);
        var routeStart = new DispatchRouteStartPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                orders, accounts);
        var handoff = new OperationalHandoffPersistenceAdapter(jdbc, changeFeed, warehouseHandoff,
                handoffNotifications, orders, accounts);
        LogisticsOperationsService logistics = new LogisticsOperationsService(dispatchQueries, dispatchCommands,
                accounts, new com.nexa.api.fulfillmentdelivery.application.service.StartDispatchRouteService(
                routeStart, warehouseHandoff), handoff, access);
        MobileDeliveryContractService mobileDeliveryContracts = new MobileDeliveryContractService(
                new JdbcMobileDeliveryContractAdapter(jdbc, orders, bindings.canonicalOutbox()), accounts,
                bindings.traceabilityCommands(), bindings.salesOrderCommands(),
                orders, clock, handoffTtl);

        DispatchWorkflowCommands dispatchWorkflowCommands = (context, reservationId, version, key) ->
                logistics.create(context, reservationId, version, key);
        return new FulfillmentDeliveryComposition(lifecycle, workList, outgoing, driverDeliveries,
                driverTracking, buyerTracking, logistics, readiness, driverAssignments, dispatchWorkflowCommands,
                mobileDeliveryContracts, dispatchWindowPlans, deliveryLoads, deliveryInstructions,
                executionTemperatures, driverDeliveryIncidents, exceptionOperations,
                bomOperationalExceptions, customerInstructions);
    }

}
