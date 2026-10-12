package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessTraceabilityBindingsFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.NotificationProjection;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels.PushNotificationCandidate;
import com.nexa.api.notifications.application.publicapi.NotificationProjectionModels;
import com.nexa.api.notifications.application.publicapi.NotificationRecipientPreflightPort;
import com.nexa.api.notifications.application.publicapi.PreflightedNotificationRecipients;
import com.nexa.api.notifications.tenantdatabase.TenantNotificationBusinessBindingsFactory;
import com.nexa.api.shared.events.TenantOutboxEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Delivers durable BC-10 push work after its source projection transaction commits. */
@Component
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public final class TenantNotificationPushDeliveryConsumer implements TenantOutboxEventConsumer {
    private static final String CONSUMER = "notifications-push-delivery-v1";
    private static final String EVENT_TYPE = "NOTIFICATION_PUSH_DELIVERY_REQUESTED";
    private static final Set<String> SOURCE_EVENTS = Set.of("PURCHASE_REQUEST_SUBMITTED",
            "PURCHASE_REQUEST_APPROVED", "SALES_ORDER_CONFIRMED", "DISPATCH_DELIVERED",
            "DeliveryCompleted.v1", "DELIVERY_COMPLETED", "POD_COMPLETED", "PAYMENT_SUCCEEDED");

    private final NotificationRecipientPreflightPort recipientPreflight;
    private final TenantNotificationBusinessBindingsFactory notifications;
    private final TenantCustomerAccountQueryFactory customerAccounts;
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;
    private final ObjectMapper mapper;

    public TenantNotificationPushDeliveryConsumer(NotificationRecipientPreflightPort recipientPreflight,
            TenantNotificationBusinessBindingsFactory notifications,
            TenantCustomerAccountQueryFactory customerAccounts,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings, ObjectMapper mapper) {
        this.recipientPreflight = Objects.requireNonNull(recipientPreflight,
                "Central notification recipient preflight is required");
        this.notifications = Objects.requireNonNull(notifications,
                "Tenant notification composition factory is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts,
                "Tenant BC-02 account query factory is required");
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings,
                "Tenant canonical outbox bindings are required");
        this.mapper = Objects.requireNonNull(mapper, "Outbox payload mapper is required");
    }

    @Override public String consumerName() { return CONSUMER; }
    @Override public Set<String> eventTypes() { return Set.of(EVENT_TYPE); }

    @Override
    public AfterTenantRead readTenantSnapshot(JdbcTemplate tenantJdbc, TenantOutboxEvent event) {
        if (!EVENT_TYPE.equals(event.eventType()) || !"NotificationPushDelivery".equals(event.aggregateType())) {
            throw new IllegalArgumentException("Unexpected Tenant notification push work event");
        }
        PushNotificationCandidate candidate = candidate(event, payload(event.payload()));
        UUID sourceEventId = UUID.fromString(candidate.projection().eventId());
        Set<UUID> requestedRecipients = candidate.projection().recipientMembershipIds().stream()
                .map(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        return () -> {
            Set<UUID> eligible = recipientPreflight.findEligibleMembershipIds(
                    UUID.fromString(candidate.projection().tenantId()),
                    UUID.fromString(candidate.projection().workspaceId()), candidate.projection().eventType(),
                    candidate.projection().aggregateType(), UUID.fromString(candidate.projection().aggregateId()),
                    optionalUuid(candidate.projection().clientAccountId()), requestedRecipients);
            Set<UUID> currentRecipients = requestedRecipients.stream().filter(eligible::contains)
                    .collect(Collectors.toUnmodifiableSet());
            PreflightedNotificationRecipients recipients = new PreflightedNotificationRecipients(
                    event.tenantId(), event.workspaceId(), currentRecipients);
            return (jdbc, transactionManager, current) -> deliver(jdbc, transactionManager, event, current,
                    candidate, recipients);
        };
    }

    private void deliver(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            TenantOutboxEvent expected, TenantOutboxEvent current, PushNotificationCandidate candidate,
            PreflightedNotificationRecipients recipients) {
        requireSameEvent(expected, current);
        TenantNotificationBusinessBindingsFactory.Bindings bindings = notifications.bindTo(jdbc,
                transactionManager, recipients, customerAccounts.bindTo(jdbc),
                traceabilityBindings.bindTo(jdbc).canonicalOutbox());
        NotificationProjection source = candidate.projection();
        NotificationProjection authorized = new NotificationProjection(source.eventId(), source.tenantId(),
                source.workspaceId(), source.clientAccountId(), source.aggregateType(), source.aggregateId(),
                source.eventType(), source.publicStatus(), source.occurredAt(),
                recipients.membershipIds().stream().map(UUID::toString).collect(Collectors.toUnmodifiableSet()),
                source.sourcePayloadSha256());
        bindings.projection().deliverPush(new PushNotificationCandidate(authorized, candidate.category(),
                candidate.title(), candidate.message(), candidate.deepLink()));
    }

    private static PushNotificationCandidate candidate(TenantOutboxEvent event, Map<String, Object> payload) {
        UUID sourceEventId = uuid(payload.get("sourceEventId"));
        UUID tenantId = uuid(payload.get("tenantId"));
        UUID workspaceId = uuid(payload.get("workspaceId"));
        UUID aggregateId = uuid(payload.get("aggregateId"));
        String sourceHash = requiredString(payload.get("sourcePayloadSha256"));
        if (!sourceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Tenant notification push source fingerprint is invalid");
        }
        if (!event.tenantId().equals(tenantId) || !event.workspaceId().equals(workspaceId)
                || !sourceEventId.equals(event.causationId())
                || !event.aggregateId().equals(UUID.nameUUIDFromBytes(
                        ("notification-push:" + sourceEventId).getBytes(StandardCharsets.UTF_8)))) {
            throw new IllegalArgumentException("Tenant notification push work escaped its source scope");
        }
        String sourceEventType = requiredString(payload.get("eventType"));
        if (!SOURCE_EVENTS.contains(sourceEventType)) {
            throw new IllegalArgumentException("Tenant notification push work has an unsupported source event");
        }
        Set<String> recipients = recipientIds(payload.get("recipientMembershipIds"));
        NotificationProjection projection = new NotificationProjection(sourceEventId.toString(), tenantId.toString(),
                workspaceId.toString(), optionalString(payload.get("clientAccountId")),
                requiredString(payload.get("aggregateType")), aggregateId.toString(), sourceEventType,
                optionalString(payload.get("publicStatus")), Instant.parse(requiredString(payload.get("occurredAt"))),
                recipients, sourceHash);
        return new PushNotificationCandidate(projection, requiredString(payload.get("category")),
                requiredString(payload.get("title")), requiredString(payload.get("message")),
                optionalString(payload.get("deepLink")));
    }

    private Map<String, Object> payload(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) {
            throw new IllegalArgumentException("Tenant notification push payload is invalid", exception);
        }
    }

    private static Set<String> recipientIds(Object value) {
        if (!(value instanceof Collection<?> values)) {
            throw new IllegalArgumentException("Tenant notification push recipients are invalid");
        }
        Set<String> recipients = values.stream().map(TenantNotificationPushDeliveryConsumer::requiredString)
                .peek(UUID::fromString).collect(Collectors.toUnmodifiableSet());
        if (recipients.isEmpty() || recipients.size() != values.size()) {
            throw new IllegalArgumentException("Tenant notification push recipients are incomplete or duplicated");
        }
        return recipients;
    }

    private static void requireSameEvent(TenantOutboxEvent expected, TenantOutboxEvent current) {
        if (!expected.equals(current)) {
            throw new IllegalStateException("Tenant notification push work changed after preflight");
        }
    }

    private static UUID uuid(Object value) {
        return UUID.fromString(requiredString(value));
    }

    private static UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static String requiredString(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Tenant notification push payload is incomplete");
        }
        return String.valueOf(value);
    }

    private static String optionalString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
