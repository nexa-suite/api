package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.bootstrap.runtime.boundaries.TenantWarehouseOperationsCompositionBinder;
import com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsEventContextQueryPort;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseEventContextQueryPort;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseEventContextQueryFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Preserves the existing legacy Sales Order reservation callback on Tenant owner services. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantSalesOrderConfirmedInventoryReservationConsumer implements TenantOutboxEventConsumer {
    private static final String EVENT_TYPE = "SALES_ORDER_CONFIRMED";
    private static final String CONSUMER = "inventory-sales-order-confirmed-reservation-v1";

    private final TenantSalesOrderFulfillmentQueryFactory salesOrders;
    private final TenantWarehouseEventContextQueryFactory warehouseEvents;
    private final TenantWarehouseOperationsCompositionBinder warehouseComposition;
    private final VerifiedSystemWorkflowAccessContextResolver workflowActors;
    private final WarehouseObjectAccess warehouseAccess;
    private final OperationalSettingsAccess operationalSettings;
    private final ObjectMapper mapper;

    public TenantSalesOrderConfirmedInventoryReservationConsumer(
            TenantSalesOrderFulfillmentQueryFactory salesOrders,
            TenantWarehouseEventContextQueryFactory warehouseEvents,
            TenantWarehouseOperationsCompositionBinder warehouseComposition,
            VerifiedSystemWorkflowAccessContextResolver workflowActors,
            WarehouseObjectAccess warehouseAccess, OperationalSettingsAccess operationalSettings,
            ObjectMapper mapper) {
        this.salesOrders = Objects.requireNonNull(salesOrders, "Tenant Sales order query factory is required");
        this.warehouseEvents = Objects.requireNonNull(warehouseEvents, "Tenant Warehouse event query factory is required");
        this.warehouseComposition = Objects.requireNonNull(warehouseComposition,
                "Tenant Warehouse composition binder is required");
        this.workflowActors = Objects.requireNonNull(workflowActors, "Verified workflow actor resolver is required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Central Warehouse access is required");
        this.operationalSettings = Objects.requireNonNull(operationalSettings,
                "Central operational settings are required");
        this.mapper = Objects.requireNonNull(mapper, "Outbox payload mapper is required");
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(EVENT_TYPE); }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        if (!EVENT_TYPE.equals(event.eventType()) || !"SalesOrder".equals(event.aggregateType())) {
            throw new IllegalArgumentException("Unexpected Tenant Inventory event type or aggregate");
        }
        Map<String, Object> payload = payload(event.payload());
        UUID salesOrderId = uuid(payload.getOrDefault("salesOrderId", event.aggregateId()));
        SalesOrderFulfillmentQuery.Snapshot order = salesOrders.bindTo(tenantJdbc)
                .get(event.tenantId(), event.workspaceId(), salesOrderId);
        Optional<WarehouseEventContextQueryPort.ReservationSnapshot> reservation = warehouseEvents.bindTo(tenantJdbc)
                .findReservationForSalesOrder(event.tenantId(), event.workspaceId(), salesOrderId);
        long expectedVersion = number(payload.get("salesOrderVersion"), order.version());
        return () -> {
            if (order.commercialCommitmentId() != null || reservation.isPresent()) {
                return (jdbc, tx, current) -> requireSameEvent(event, current);
            }
            CurrentAccessContext actor = workflowActors.resolve(event.tenantId(), event.workspaceId());
            if (!event.tenantId().equals(actor.tenantId().value())
                    || !event.workspaceId().equals(actor.workspaceId().value())) {
                throw new IllegalStateException("Resolved SYSTEM_WORKFLOW actor escaped the event Tenant scope");
            }
            Set<UUID> activeWarehouseIds = Set.copyOf(warehouseAccess.activeWarehouseIds(actor));
            Optional<OperationalSettingsAccess.Snapshot> settings = operationalSettings.find(
                    event.workspaceId().toString());
            return (jdbc, tx, current) -> {
                requireSameEvent(event, current);
                SalesOrderFulfillmentQuery.Snapshot currentOrder = salesOrders.bindTo(jdbc)
                        .get(current.tenantId(), current.workspaceId(), salesOrderId);
                if (currentOrder.commercialCommitmentId() != null
                        || warehouseEvents.bindTo(jdbc).findReservationForSalesOrder(
                                current.tenantId(), current.workspaceId(), salesOrderId).isPresent()) return;
                WarehouseOperationsService service = warehouseComposition.bindTo(jdbc, actor,
                        activeWarehouseIds, settings);
                service.reserve(actor, salesOrderId.toString(), expectedVersion,
                        "outbox-reservation-" + current.eventId(), current.correlationId());
            };
        };
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Tenant Sales event payload is invalid", exception); }
    }

    private static void requireSameEvent(TenantOutboxEvent expected, TenantOutboxEvent current) {
        if (!expected.eventId().equals(current.eventId()) || !expected.eventType().equals(current.eventType())
                || !expected.aggregateType().equals(current.aggregateType())
                || !expected.aggregateId().equals(current.aggregateId())
                || !expected.tenantId().equals(current.tenantId()) || !expected.workspaceId().equals(current.workspaceId())
                || !expected.payloadSha256().equals(current.payloadSha256())) {
            throw new IllegalStateException("Tenant Inventory source event changed after preflight");
        }
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value == null) throw new IllegalArgumentException("Sales Order event id is required");
        return UUID.fromString(String.valueOf(value));
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue()
                : value == null ? fallback : Long.parseLong(String.valueOf(value));
    }
}
