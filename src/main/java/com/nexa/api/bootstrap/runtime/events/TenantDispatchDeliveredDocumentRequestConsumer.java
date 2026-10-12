package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessDocumentBindingsFactory;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentDocumentSourceQuery;
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

/** Requests accepted documents after legacy dispatch delivery or immutable POD finalization. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantDispatchDeliveredDocumentRequestConsumer implements TenantOutboxEventConsumer {
    private static final String DISPATCH_EVENT_TYPE = "DISPATCH_DELIVERED";
    private static final String DELIVERY_EVENT_TYPE = "DeliveryCompleted.v1";
    private static final Set<String> EVENT_TYPES = Set.of(DISPATCH_EVENT_TYPE, DELIVERY_EVENT_TYPE);
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
    @Override public Set<String> eventTypes() { return EVENT_TYPES; }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        if (!EVENT_TYPES.contains(event.eventType())) {
            throw new IllegalArgumentException("Unexpected Tenant Business Document event type or aggregate");
        }
        Map<String, Object> payload = payload(event.payload());
        boolean dispatchDelivered = DISPATCH_EVENT_TYPE.equals(event.eventType());
        String expectedAggregateType = dispatchDelivered ? "DispatchOrder" : "Delivery";
        if (!expectedAggregateType.equals(event.aggregateType())) {
            throw new IllegalArgumentException("Unexpected Tenant Business Document aggregate type");
        }
        UUID subjectId = matchingPayloadId(payload, dispatchDelivered ? "dispatchOrderId" : "deliveryId",
                event.aggregateId());
        UUID eventPodId = optionalUuid(payload.get("podId"));
        UUID podId = eventPodId;
        UUID salesOrderId = dispatchDelivered ? optionalUuid(payload.get("salesOrderId")) : null;
        if (!dispatchDelivered) {
            FulfillmentDocumentSourceQuery source = documents.bindFulfillmentSource(tenantJdbc);
            FulfillmentDocumentSourceQuery.ProofOfDelivery pod = (eventPodId == null
                    ? source.findSealedPodForDelivery(event.tenantId(), event.workspaceId(), subjectId)
                    : source.findPod(event.tenantId(), event.workspaceId(), eventPodId))
                    .orElseThrow(() -> new IllegalStateException(
                            "Delivery completion event has no immutable Tenant POD source"));
            if (!"SEALED".equals(pod.status()) || !subjectId.equals(pod.deliveryId())) {
                throw new IllegalStateException("Delivery completion event does not match its sealed Tenant POD source");
            }
            podId = pod.id();
            salesOrderId = pod.salesOrderId();
            if (salesOrderId == null) {
                throw new IllegalStateException("Sealed Tenant POD has no Sales Order document source");
            }
        }
        UUID finalPodId = podId;
        UUID finalSalesOrderId = salesOrderId;
        return () -> {
            CurrentAccessContext actor = workflowActors.resolve(event.tenantId(), event.workspaceId());
            if (!event.tenantId().equals(actor.tenantId().value())
                    || !event.workspaceId().equals(actor.workspaceId().value())) {
                throw new IllegalStateException("Resolved SYSTEM_WORKFLOW actor escaped the event Tenant scope");
            }
            Set<UUID> activeWarehouseIds = Set.copyOf(warehouseAccess.activeWarehouseIds(actor));
            return (jdbc, transactionManager, current) -> {
                requireSameEvent(event, current);
                var commands = documents.bindRequest(jdbc, actor, activeWarehouseIds);
                if (dispatchDelivered) {
                    commands.request(actor, "DISPATCH_ORDER", subjectId, "DELIVERY_GUIDE_DRAFT", "PDF",
                            "outbox-" + current.eventId() + "-delivery-guide");
                }
                if (finalPodId != null) {
                    commands.request(actor, "PROOF_OF_DELIVERY", finalPodId, "POD_REPORT", "PDF",
                            dispatchDelivered ? "outbox-" + current.eventId() + "-pod-report"
                                    : "pod-finalization-" + finalPodId + "-pod-report");
                }
                if (dispatchDelivered && finalSalesOrderId != null) {
                    commands.request(actor, "SALES_ORDER", finalSalesOrderId, "ORDER_SUMMARY", "PDF",
                            "outbox-" + current.eventId() + "-order-summary-pdf");
                    commands.request(actor, "SALES_ORDER", finalSalesOrderId, "ORDER_SUMMARY", "CSV",
                            "outbox-" + current.eventId() + "-order-summary-csv");
                } else if (!dispatchDelivered && finalSalesOrderId != null) {
                    String finalizationKey = "pod-finalization-" + finalPodId;
                    commands.request(actor, "SALES_ORDER", finalSalesOrderId, "ORDER_SUMMARY", "PDF",
                            finalizationKey + "-order-summary-pdf");
                    commands.request(actor, "SALES_ORDER", finalSalesOrderId, "ORDER_SUMMARY", "CSV",
                            finalizationKey + "-order-summary-csv");
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
            throw new IllegalArgumentException("Business document event escaped its aggregate scope");
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
