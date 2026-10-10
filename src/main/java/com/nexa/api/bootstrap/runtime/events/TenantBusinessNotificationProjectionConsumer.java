package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.creditreceivables.tenantdatabase.TenantCreditAccountAdapterFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerMembershipQueryFactory;
import com.nexa.api.fulfillmentdelivery.application.publicapi.LogisticsEventContextQueryPort;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantLogisticsEventContextQueryFactory;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection;
import com.nexa.api.notifications.application.publicapi.NotificationRecipientPreflightPort;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;
import com.nexa.api.notifications.application.publicapi.TenantNotificationBusinessBindingsFactory;
import com.nexa.api.salescommitment.application.publicapi.SalesWorkflowEventSnapshotQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesWorkflowEventSnapshotQueryFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/** Projects accepted business events to BC-10 after central recipient preflight. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantBusinessNotificationProjectionConsumer implements TenantOutboxEventConsumer {
    private static final String CONSUMER = "notifications-business-projection-v1";
    private static final Set<String> EVENT_TYPES = Set.of("PURCHASE_REQUEST_SUBMITTED", "PURCHASE_REQUEST_APPROVED",
            "SALES_ORDER_CONFIRMED", "DISPATCH_DELIVERED", "DELIVERY_COMPLETED", "POD_COMPLETED",
            "PAYMENT_SUCCEEDED");

    private final TenantSalesWorkflowEventSnapshotQueryFactory salesSnapshots;
    private final TenantLogisticsEventContextQueryFactory logisticsSnapshots;
    private final TenantCreditAccountAdapterFactory creditAdapters;
    private final TenantCustomerMembershipQueryFactory customerMemberships;
    private final NotificationRecipientPreflightPort recipientPreflight;
    private final TenantNotificationBusinessBindingsFactory notifications;
    private final ObjectMapper mapper;

    public TenantBusinessNotificationProjectionConsumer(TenantSalesWorkflowEventSnapshotQueryFactory salesSnapshots,
            TenantLogisticsEventContextQueryFactory logisticsSnapshots,
            TenantCreditAccountAdapterFactory creditAdapters,
            TenantCustomerMembershipQueryFactory customerMemberships,
            NotificationRecipientPreflightPort recipientPreflight,
            TenantNotificationBusinessBindingsFactory notifications, ObjectMapper mapper) {
        this.salesSnapshots = Objects.requireNonNull(salesSnapshots, "Tenant Sales event snapshot factory is required");
        this.logisticsSnapshots = Objects.requireNonNull(logisticsSnapshots,
                "Tenant Logistics event snapshot factory is required");
        this.creditAdapters = Objects.requireNonNull(creditAdapters, "Tenant Credit factory is required");
        this.customerMemberships = Objects.requireNonNull(customerMemberships,
                "Tenant Buyer membership factory is required");
        this.recipientPreflight = Objects.requireNonNull(recipientPreflight,
                "Central notification recipient preflight is required");
        this.notifications = Objects.requireNonNull(notifications,
                "Tenant notification composition factory is required");
        this.mapper = Objects.requireNonNull(mapper, "Outbox payload mapper is required");
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return EVENT_TYPES; }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        if (!EVENT_TYPES.contains(event.eventType())) throw new IllegalArgumentException("Unexpected Tenant notification event type");
        Map<String, Object> payload = payload(event.payload());
        UUID clientAccountId = clientAccountId(tenantJdbc, event, payload);
        Set<UUID> tenantBuyerMembershipIds = clientAccountId == null
                || !Set.of("DISPATCH_DELIVERED", "PAYMENT_SUCCEEDED").contains(event.eventType())
                ? Set.of() : Set.copyOf(customerMemberships.bindTo(tenantJdbc)
                        .findMembershipIds(event.tenantId(), event.workspaceId(), clientAccountId));
        return () -> {
            Set<UUID> recipientIds = recipientPreflight.findEligibleMembershipIds(event.tenantId(),
                    event.workspaceId(), event.eventType(), event.aggregateType(), event.aggregateId(), clientAccountId,
                    tenantBuyerMembershipIds);
            PreflightedNotificationRecipients recipients = new PreflightedNotificationRecipients(event.tenantId(),
                    event.workspaceId(), recipientIds);
            Map<String, Object> immutablePayload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
            TenantOutboxEvent expected = event;
            return (jdbc, transactionManager, current) -> project(jdbc, transactionManager, expected, current,
                    immutablePayload, clientAccountId, recipients);
        };
    }

    private void project(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            TenantOutboxEvent expected, TenantOutboxEvent event,
            Map<String, Object> payload, UUID clientAccountId, PreflightedNotificationRecipients recipients) {
        if (!expected.eventId().equals(event.eventId()) || !expected.eventType().equals(event.eventType())
                || !expected.aggregateType().equals(event.aggregateType())
                || !expected.aggregateId().equals(event.aggregateId())
                || !expected.tenantId().equals(event.tenantId()) || !expected.workspaceId().equals(event.workspaceId())
                || !expected.occurredAt().equals(event.occurredAt())
                || !expected.payloadSha256().equals(event.payloadSha256())
                || !event.tenantId().equals(recipients.tenantId())
                || !event.workspaceId().equals(recipients.workspaceId())) {
            throw new IllegalStateException("Notification projection escaped its preflighted Tenant event");
        }
        if (recipients.membershipIds().isEmpty()) return;
        TenantNotificationBusinessBindingsFactory.Bindings bindings = notifications.bindTo(jdbc,
                transactionManager, recipients);
        NotificationProjection projection = new NotificationProjection(event.eventId().toString(),
                event.tenantId().toString(), event.workspaceId().toString(),
                clientAccountId == null ? null : clientAccountId.toString(), event.aggregateType(),
                event.aggregateId().toString(), event.eventType(), string(payload.get("status")),
                event.occurredAt(), recipients.membershipIds().stream().map(UUID::toString).collect(Collectors.toUnmodifiableSet()),
                com.nexa.api.notifications.application.publicapi.NotificationProjectionModels
                        .sourcePayloadSha256(event.payload()));
        bindings.projection().project(projection);
    }

    private UUID clientAccountId(JdbcTemplate jdbc, TenantOutboxEvent event, Map<String, Object> payload) {
        Object explicit = payload.get("clientAccountId");
        if (explicit != null) return uuid(explicit);
        return switch (event.aggregateType()) {
            case "PurchaseRequest" -> salesSnapshots.bindTo(jdbc)
                    .findPurchaseRequest(event.tenantId(), event.workspaceId(), event.aggregateId())
                    .map(SalesWorkflowEventSnapshotQuery.PurchaseRequestSnapshot::clientAccountId).orElse(null);
            case "SalesOrder" -> salesSnapshots.bindTo(jdbc)
                    .findSalesOrder(event.tenantId(), event.workspaceId(), event.aggregateId())
                    .map(SalesWorkflowEventSnapshotQuery.SalesOrderSnapshot::clientAccountId).orElse(null);
            case "DispatchOrder" -> logisticsSnapshots.bindTo(jdbc)
                    .findDispatch(event.tenantId(), event.workspaceId(), event.aggregateId())
                    .map(LogisticsEventContextQueryPort.DispatchSnapshot::clientAccountId).orElse(null);
            case "ProofOfDelivery" -> {
                Object dispatchId = payload.get("dispatchOrderId");
                yield dispatchId == null ? null : logisticsSnapshots.bindTo(jdbc)
                        .findDispatch(event.tenantId(), event.workspaceId(), uuid(dispatchId))
                        .map(LogisticsEventContextQueryPort.DispatchSnapshot::clientAccountId).orElse(null);
            }
            case "Receivable" -> creditAdapters.bindReceivablePaymentAccessTo(jdbc)
                    .find(event.tenantId(), event.workspaceId(), event.aggregateId())
                    .map(ReceivablePaymentAccess.Snapshot::clientAccountId).orElse(null);
            case "Payment" -> {
                Object receivableId = payload.get("receivableId");
                yield receivableId == null ? null : creditAdapters.bindReceivablePaymentAccessTo(jdbc)
                        .find(event.tenantId(), event.workspaceId(), uuid(receivableId))
                        .map(ReceivablePaymentAccess.Snapshot::clientAccountId).orElse(null);
            }
            default -> null;
        };
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Tenant notification event payload is invalid", exception); }
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value == null) throw new IllegalArgumentException("Tenant event reference id is required");
        return UUID.fromString(String.valueOf(value));
    }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }
}
