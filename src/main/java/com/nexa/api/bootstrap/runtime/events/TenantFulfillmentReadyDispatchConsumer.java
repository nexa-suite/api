package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.bootstrap.runtime.boundaries.TenantLogisticsEventCompositionBinder;
import com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsEventContextQueryPort;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseEventContextQueryPort;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseEventContextQueryFactory;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantLogisticsEventContextQueryFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Replays the existing ready-reservation dispatch creation through Tenant BC-06. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantFulfillmentReadyDispatchConsumer implements TenantOutboxEventConsumer {
    private static final String EVENT_TYPE = "FULFILLMENT_READY";
    private static final String CONSUMER = "fulfillment-ready-dispatch-v1";

    private final TenantWarehouseEventContextQueryFactory reservations;
    private final TenantLogisticsEventContextQueryFactory logisticsEvents;
    private final TenantLogisticsEventCompositionBinder logistics;
    private final VerifiedSystemWorkflowAccessContextResolver workflowActors;
    private final ObjectMapper mapper;

    public TenantFulfillmentReadyDispatchConsumer(TenantWarehouseEventContextQueryFactory reservations,
            TenantLogisticsEventContextQueryFactory logisticsEvents,
            TenantLogisticsEventCompositionBinder logistics,
            VerifiedSystemWorkflowAccessContextResolver workflowActors, ObjectMapper mapper) {
        this.reservations = Objects.requireNonNull(reservations, "Tenant Warehouse event query factory is required");
        this.logisticsEvents = Objects.requireNonNull(logisticsEvents, "Tenant Logistics event query factory is required");
        this.logistics = Objects.requireNonNull(logistics, "Tenant Logistics composition binder is required");
        this.workflowActors = Objects.requireNonNull(workflowActors, "Verified workflow actor resolver is required");
        this.mapper = Objects.requireNonNull(mapper, "Outbox payload mapper is required");
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(EVENT_TYPE); }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        if (!EVENT_TYPE.equals(event.eventType()) || !"InventoryReservation".equals(event.aggregateType())) {
            throw new IllegalArgumentException("Unexpected Tenant Fulfillment event type or aggregate");
        }
        Map<String, Object> payload = payload(event.payload());
        UUID reservationId = uuid(payload.getOrDefault("reservationId", event.aggregateId()));
        long expectedVersion = number(payload.get("reservationVersion"), reservations.bindTo(tenantJdbc)
                .findReservation(event.tenantId(), event.workspaceId(), reservationId)
                .orElseThrow(() -> new IllegalStateException("Tenant inventory reservation context is unavailable"))
                .version());
        boolean dispatchExists = logisticsEvents.bindTo(tenantJdbc)
                .findDispatchByReservation(event.tenantId(), event.workspaceId(), reservationId).isPresent();
        return () -> {
            CurrentAccessContext actor = workflowActors.resolve(event.tenantId(), event.workspaceId());
            requireActorScope(actor, event);
            TenantLogisticsEventCompositionBinder.Preflight access = logistics.preflight(actor);
            return (jdbc, transactionManager, current) -> {
                requireSameEvent(event, current);
                if (dispatchExists || logisticsEvents.bindTo(jdbc)
                        .findDispatchByReservation(current.tenantId(), current.workspaceId(), reservationId).isPresent()) {
                    return;
                }
                logistics.bindTo(jdbc, access).logistics().create(actor, reservationId.toString(), expectedVersion,
                        "outbox-dispatch-" + current.eventId());
            };
        };
    }

    private static void requireActorScope(CurrentAccessContext actor, TenantOutboxEvent event) {
        if (!event.tenantId().equals(actor.tenantId().value())
                || !event.workspaceId().equals(actor.workspaceId().value())) {
            throw new IllegalStateException("Resolved SYSTEM_WORKFLOW actor escaped the event Tenant scope");
        }
    }

    private static void requireSameEvent(TenantOutboxEvent expected, TenantOutboxEvent current) {
        if (!expected.eventId().equals(current.eventId()) || !expected.eventType().equals(current.eventType())
                || !expected.aggregateType().equals(current.aggregateType())
                || !expected.aggregateId().equals(current.aggregateId())
                || !expected.tenantId().equals(current.tenantId()) || !expected.workspaceId().equals(current.workspaceId())
                || !expected.occurredAt().equals(current.occurredAt())
                || !expected.payloadSha256().equals(current.payloadSha256())) {
            throw new IllegalStateException("Tenant Fulfillment event changed after preflight");
        }
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Tenant Fulfillment event payload is invalid", exception); }
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value == null) throw new IllegalArgumentException("Inventory reservation event id is required");
        return UUID.fromString(String.valueOf(value));
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue()
                : value == null ? fallback : Long.parseLong(String.valueOf(value));
    }
}
