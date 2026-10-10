package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessDocumentBindingsFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
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

/** Preserves the accepted commercial document requests after dispatch delivery. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantDispatchDeliveredDocumentRequestConsumer implements TenantOutboxEventConsumer {
    private static final String EVENT_TYPE = "DISPATCH_DELIVERED";
    private static final String CONSUMER = "business-documents-dispatch-delivered-v1";

    private final TenantBusinessDocumentBindingsFactory documents;
    private final VerifiedSystemWorkflowAccessContextResolver workflowActors;
    private final WarehouseObjectAccess warehouseAccess;
    private final ObjectMapper mapper;

    public TenantDispatchDeliveredDocumentRequestConsumer(TenantBusinessDocumentBindingsFactory documents,
            VerifiedSystemWorkflowAccessContextResolver workflowActors, WarehouseObjectAccess warehouseAccess,
            ObjectMapper mapper) {
        this.documents = Objects.requireNonNull(documents, "Tenant Business Document bindings are required");
        this.workflowActors = Objects.requireNonNull(workflowActors, "Verified workflow actor resolver is required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Central Warehouse access is required");
        this.mapper = Objects.requireNonNull(mapper, "Outbox payload mapper is required");
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(EVENT_TYPE); }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        if (!EVENT_TYPE.equals(event.eventType()) || !"DispatchOrder".equals(event.aggregateType())) {
            throw new IllegalArgumentException("Unexpected Tenant Business Document event type or aggregate");
        }
        Map<String, Object> payload = payload(event.payload());
        UUID dispatchId = matchingPayloadId(payload, "dispatchOrderId", event.aggregateId());
        UUID podId = optionalUuid(payload.get("podId"));
        UUID salesOrderId = optionalUuid(payload.get("salesOrderId"));
        return () -> {
            CurrentAccessContext actor = workflowActors.resolve(event.tenantId(), event.workspaceId());
            if (!event.tenantId().equals(actor.tenantId().value())
                    || !event.workspaceId().equals(actor.workspaceId().value())) {
                throw new IllegalStateException("Resolved SYSTEM_WORKFLOW actor escaped the event Tenant scope");
            }
            Set<UUID> activeWarehouseIds = Set.copyOf(warehouseAccess.activeWarehouseIds(actor));
            return (jdbc, transactionManager, current) -> {
                requireSameEvent(event, current);
                var commands = documents.bindRequest(jdbc, actor, activeWarehouseIds).documents();
                commands.request(actor, "DISPATCH_ORDER", dispatchId, "DELIVERY_GUIDE_DRAFT", "PDF",
                        "outbox-" + current.eventId() + "-delivery-guide");
                if (podId != null) {
                    commands.request(actor, "PROOF_OF_DELIVERY", podId, "POD_REPORT", "PDF",
                            "outbox-" + current.eventId() + "-pod-report");
                }
                if (salesOrderId != null) {
                    commands.request(actor, "SALES_ORDER", salesOrderId, "ORDER_SUMMARY", "PDF",
                            "outbox-" + current.eventId() + "-order-summary-pdf");
                    commands.request(actor, "SALES_ORDER", salesOrderId, "ORDER_SUMMARY", "CSV",
                            "outbox-" + current.eventId() + "-order-summary-csv");
                }
            };
        };
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Tenant Business Document event payload is invalid", exception); }
    }

    private static UUID matchingPayloadId(Map<String, Object> payload, String name, UUID aggregateId) {
        UUID supplied = optionalUuid(payload.get(name));
        if (supplied != null && !supplied.equals(aggregateId)) {
            throw new IllegalArgumentException("Dispatch document event escaped its aggregate scope");
        }
        return aggregateId;
    }

    private static UUID optionalUuid(Object value) {
        if (value == null) return null;
        if (value instanceof UUID id) return id;
        return UUID.fromString(String.valueOf(value));
    }

    private static void requireSameEvent(TenantOutboxEvent expected, TenantOutboxEvent current) {
        if (!expected.eventId().equals(current.eventId()) || !expected.eventType().equals(current.eventType())
                || !expected.aggregateType().equals(current.aggregateType())
                || !expected.aggregateId().equals(current.aggregateId())
                || !expected.tenantId().equals(current.tenantId()) || !expected.workspaceId().equals(current.workspaceId())
                || !expected.occurredAt().equals(current.occurredAt())
                || !expected.payloadSha256().equals(current.payloadSha256())) {
            throw new IllegalStateException("Tenant Business Document event changed after preflight");
        }
    }
}
