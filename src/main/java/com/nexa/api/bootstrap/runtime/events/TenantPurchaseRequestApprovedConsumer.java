package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.bootstrap.runtime.boundaries.TenantSalesCommitmentCompositionProvider;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesWorkflowEventSnapshotQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesWorkflowEventSnapshotQueryFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.beans.factory.ObjectProvider;
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

/** Replays the accepted Purchase Request approval conversion on its Tenant owner composition. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantPurchaseRequestApprovedConsumer implements TenantOutboxEventConsumer {
    private static final String EVENT_TYPE = "PURCHASE_REQUEST_APPROVED";
    private static final String CONSUMER = "sales-purchase-request-approved-v1";

    private final TenantSalesWorkflowEventSnapshotQueryFactory snapshots;
    private final ObjectProvider<TenantSalesCommitmentCompositionProvider> salesCompositions;
    private final VerifiedSystemWorkflowAccessContextResolver workflowActors;
    private final ObjectMapper mapper;

    public TenantPurchaseRequestApprovedConsumer(TenantSalesWorkflowEventSnapshotQueryFactory snapshots,
            ObjectProvider<TenantSalesCommitmentCompositionProvider> salesCompositions,
            VerifiedSystemWorkflowAccessContextResolver workflowActors, ObjectMapper mapper) {
        this.snapshots = Objects.requireNonNull(snapshots, "Tenant Sales event snapshot factory is required");
        this.salesCompositions = Objects.requireNonNull(salesCompositions, "Tenant Sales composition provider is required");
        this.workflowActors = Objects.requireNonNull(workflowActors, "Verified workflow actor resolver is required");
        this.mapper = Objects.requireNonNull(mapper, "Outbox payload mapper is required");
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(EVENT_TYPE); }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        requireEvent(event);
        Map<String, Object> payload = payload(event.payload());
        UUID requestId = uuid(payload.getOrDefault("purchaseRequestId", event.aggregateId()));
        SalesWorkflowEventSnapshotQuery query = snapshots.bindTo(tenantJdbc);
        SalesWorkflowEventSnapshotQuery.PurchaseRequestSnapshot request = query.findPurchaseRequest(
                        event.tenantId(), event.workspaceId(), requestId)
                .orElseThrow(() -> new IllegalStateException("Tenant Purchase Request context is unavailable"));
        long version = number(payload.get("purchaseRequestVersion"), request.version());
        return () -> {
            CurrentAccessContext actor = workflowActors.resolve(event.tenantId(), event.workspaceId());
            requireActorScope(actor, event);
            return (jdbc, transactionManager, current) -> {
                requireSameScope(event, current);
                if (snapshots.bindTo(jdbc).findSalesOrderBySourcePurchaseRequest(
                        current.tenantId(), current.workspaceId(), requestId).isPresent()) return;
                TenantSalesCommitmentCompositionProvider composition = salesCompositions.getIfAvailable();
                if (composition == null) {
                    throw new IllegalStateException("Tenant Sales conversion composition is not configured");
                }
                try {
                    composition.bindTo(jdbc).approvedWorkflowConversion().convertApprovedBySystemWorkflow(actor,
                            requestId.toString(), version,
                            "outbox-conversion-" + current.eventId(),
                            "Automatic conversion after purchase request approval");
                } catch (CommercialBusinessException exception) {
                    if (!"PURCHASE_REQUEST_EXPIRED".equals(exception.code())) throw exception;
                }
            };
        };
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Tenant Purchase Request event payload is invalid", exception); }
    }

    private static void requireEvent(TenantOutboxEvent event) {
        Objects.requireNonNull(event, "Tenant outbox event is required");
        if (!EVENT_TYPE.equals(event.eventType()) || !"PurchaseRequest".equals(event.aggregateType())) {
            throw new IllegalArgumentException("Unexpected Tenant Sales event type or aggregate");
        }
    }

    private static void requireActorScope(CurrentAccessContext actor, TenantOutboxEvent event) {
        if (!event.tenantId().equals(actor.tenantId().value())
                || !event.workspaceId().equals(actor.workspaceId().value())) {
            throw new IllegalStateException("Resolved SYSTEM_WORKFLOW actor escaped the event Tenant scope");
        }
    }

    private static void requireSameScope(TenantOutboxEvent expected, TenantOutboxEvent current) {
        if (!expected.eventId().equals(current.eventId()) || !expected.tenantId().equals(current.tenantId())
                || !expected.workspaceId().equals(current.workspaceId())
                || !expected.payloadSha256().equals(current.payloadSha256())) {
            throw new IllegalStateException("Tenant Sales event changed after preflight");
        }
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value == null) throw new IllegalArgumentException("Purchase Request event id is required");
        return UUID.fromString(String.valueOf(value));
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue()
                : value == null ? fallback : Long.parseLong(String.valueOf(value));
    }
}
