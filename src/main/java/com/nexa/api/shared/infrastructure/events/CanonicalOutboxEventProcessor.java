package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.businessdocuments.application.port.BusinessDocumentPort;
import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.port.out.LogisticsEventContextQueryPort;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.model.NotificationModels.PushNotificationCandidate;
import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.payments.application.port.PaymentPort;
import com.nexa.api.salescommitment.application.port.out.SalesEventContextQueryPort;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.shared.events.PaymentEventContextQueryPort;
import com.nexa.api.shared.infrastructure.observability.TechnicalMetrics;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.TenantEventContextQueryPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.shared.infrastructure.security.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.out.WarehouseEventContextQueryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, retryable consumer for the one integration outbox. Domain services
 * emit facts; this worker coordinates cross-context effects through inbound
 * application ports and records consumer idempotency in integration.inbox_event.
 */
@Component
@Profile("!test")
public final class CanonicalOutboxEventProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(CanonicalOutboxEventProcessor.class);
    private static final String CONSUMER = "nexa-service-foundation-v1";
    private static final String PUSH_DELIVERY_REQUESTED = "NOTIFICATION_PUSH_DELIVERY_REQUESTED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NotificationProjectionPort notifications;
    private final TenantEventContextQueryPort tenantContext;
    private final SalesEventContextQueryPort salesContext;
    private final WarehouseEventContextQueryPort warehouseContext;
    private final LogisticsEventContextQueryPort logisticsContext;
    private final PaymentEventContextQueryPort paymentContext;
    private final ResolveCurrentAccessContextUseCase access;
    private final SalesOrderUseCase salesOrders;
    private final WarehouseOperationsService warehouse;
    private final LogisticsOperationsService logistics;
    private final BusinessDocumentPort documents;
    private final PaymentPort payments;
    private final TechnicalMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final int outboxRetentionDays;
    private final int outboxRetentionBatchSize;

    public CanonicalOutboxEventProcessor(JdbcTemplate jdbc, ObjectMapper mapper,
                                         @Qualifier("notificationProjectionPort") NotificationProjectionPort notifications,
                                         TenantEventContextQueryPort tenantContext,
                                         SalesEventContextQueryPort salesContext,
                                         WarehouseEventContextQueryPort warehouseContext,
                                         LogisticsEventContextQueryPort logisticsContext,
                                         PaymentEventContextQueryPort paymentContext,
                                         ResolveCurrentAccessContextUseCase access,
                                         SalesOrderUseCase salesOrders,
                                         WarehouseOperationsService warehouse,
                                         LogisticsOperationsService logistics,
                                         BusinessDocumentPort documents,
                                         PaymentPort payments,
                                         ObjectProvider<TechnicalMetrics> metrics,
                                         PlatformTransactionManager transactionManager,
                                         @Value("${nexa.integration.outbox-retention-days:90}") int outboxRetentionDays,
                                         @Value("${nexa.integration.outbox-retention-batch-size:500}") int outboxRetentionBatchSize) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.notifications = notifications;
        this.tenantContext = tenantContext;
        this.salesContext = salesContext;
        this.warehouseContext = warehouseContext;
        this.logisticsContext = logisticsContext;
        this.paymentContext = paymentContext;
        this.access = access;
        this.salesOrders = salesOrders;
        this.warehouse = warehouse;
        this.logistics = logistics;
        this.documents = documents;
        this.payments = payments;
        this.metrics = metrics == null ? null : metrics.getIfAvailable();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.outboxRetentionDays = requirePositive(outboxRetentionDays, "outbox retention days");
        this.outboxRetentionBatchSize = requirePositive(outboxRetentionBatchSize, "outbox retention batch size");
        registerGauges();
    }

    @Scheduled(fixedDelayString = "${nexa.integration.outbox-delay-ms:1000}")
    public void processBatch() {
        jdbc.update("update integration.outbox_event set status=case when attempt_count >= 20 then 'DEAD_LETTER' else 'PENDING' end,next_attempt_at=current_timestamp,processing_started_at=null,lease_until=null,claim_token=null where status='PROCESSING' and lease_until <= current_timestamp");
        List<EventRow> rows = jdbc.query("select event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,correlation_id,causation_id,schema_version,payload::text,attempt_count from integration.outbox_event where status in ('PENDING','FAILED') and next_attempt_at <= current_timestamp and attempt_count < 20 order by created_at,event_id limit 20", (rs, n) -> new EventRow(
                rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("correlation_id"), rs.getObject("causation_id", UUID.class), rs.getString("schema_version"),
                rs.getString("payload"), rs.getInt("attempt_count")));
        for (EventRow row : rows) {
            UUID claimToken = UUID.randomUUID();
            if (jdbc.update("update integration.outbox_event set status='PROCESSING',attempt_count=attempt_count+1,processing_started_at=current_timestamp,lease_until=current_timestamp + interval '10 minutes',claim_token=? where event_id=? and status in ('PENDING','FAILED') and attempt_count < 20 and next_attempt_at <= current_timestamp", claimToken, row.eventId()) != 1) {
                count("claim", "lost");
                continue;
            }
            count("claim", "acquired");
            if (row.attemptCount() > 0) count("retry", "scheduled");
            TechnicalMetrics.TimerSample timer = start("process");
            try {
                RlsRequestScope.set(row.tenantId(), row.workspaceId());
                if (PUSH_DELIVERY_REQUESTED.equals(row.eventType())) {
                    // Push provider I/O is deliberately outside the source
                    // business transaction. The same canonical outbox row
                    // owns retry, lease fencing, and dead-letter handling.
                    processPushDelivery(row, claimToken);
                    transactionTemplate.executeWithoutResult(transaction -> {
                        assertClaimOwner(row.eventId(), claimToken);
                        jdbc.update("insert into integration.inbox_event(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) values (?,?,?,?,?,'PROCESSED') on conflict (consumer_name,event_id) do nothing", CONSUMER + "-push", row.eventId(), row.tenantId(), row.workspaceId(), Timestamp.from(Instant.now()));
                        finalizePublished(row, claimToken);
                    });
                } else {
                    transactionTemplate.executeWithoutResult(transaction -> {
                        assertClaimOwner(row.eventId(), claimToken);
                        processOne(row, claimToken);
                        finalizePublished(row, claimToken);
                    });
                }
                record(timer, "published");
                count("publish", "success");
            } catch (RuntimeException exception) {
                LOG.error("Canonical outbox processing failed eventId={} eventType={} correlationId={}", row.eventId(), row.eventType(), row.correlationId(), exception);
                jdbc.update("update integration.outbox_event set status=case when attempt_count >= 20 then 'DEAD_LETTER' else 'FAILED' end,next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second'),processing_started_at=null,lease_until=null,claim_token=null where event_id=? and status='PROCESSING' and claim_token=?", row.eventId(), claimToken);
                boolean deadLetter = row.attemptCount() + 1 >= 20;
                record(timer, deadLetter ? "dead_letter" : "failed");
                count("publish", deadLetter ? "dead_letter" : "failed");
            } finally {
                RlsRequestScope.clear();
            }
        }
    }

    /** Redacts old published payloads without deleting occurrence identity or inbox deduplication history. */
    @Scheduled(fixedDelayString = "${nexa.integration.outbox-retention-delay-ms:3600000}",
            initialDelayString = "${nexa.integration.outbox-retention-initial-delay-ms:3600000}")
    public void retainPublishedPayloads() {
        int redacted = jdbc.update("""
                with candidates as (
                    select event_id
                    from integration.outbox_event
                    where status='PUBLISHED'
                      and processed_at is not null
                      and processed_at < current_timestamp - (? * interval '1 day')
                      and payload <> '{}'::jsonb
                    order by processed_at,event_id
                    limit ?
                )
                update integration.outbox_event event
                   set payload='{}'::jsonb
                  from candidates
                 where event.event_id=candidates.event_id
                """, outboxRetentionDays, outboxRetentionBatchSize);
        if (redacted > 0) LOG.info("Canonical outbox retention redacted {} published payloads", redacted);
    }

    private void processOne(EventRow event, UUID claimToken) {
        assertClaimOwner(event.eventId(), claimToken);
        if (jdbc.queryForObject("select exists(select 1 from integration.inbox_event where consumer_name=? and event_id=?)", Boolean.class, CONSUMER, event.eventId())) {
            count("inbox", "deduplicated");
            return;
        }
        Map<String, Object> payload = payload(event.payload());
        switch (event.eventType()) {
            case "PURCHASE_REQUEST_SUBMITTED", "PURCHASE_REQUEST_APPROVED", "SALES_ORDER_CONFIRMED", "DISPATCH_DELIVERED", "DELIVERY_COMPLETED", "POD_COMPLETED", "PAYMENT_SUCCEEDED" -> {
                projectNotification(event, payload);
                if ("PURCHASE_REQUEST_APPROVED".equals(event.eventType())) convertApproved(event, payload);
                if ("SALES_ORDER_CONFIRMED".equals(event.eventType())) {
                    reserveConfirmed(event, payload);
                }
                if ("DISPATCH_DELIVERED".equals(event.eventType())) generateDeliveryDocuments(event, payload);
            }
            case "FULFILLMENT_READY" -> createDispatch(event, payload);
            case "INVOICE_ISSUED" -> createReceivable(event, payload);
            case "BUSINESS_DOCUMENT_GENERATION_REQUESTED" -> { /* durable fact; source service owns the generation effect */ }
            default -> { /* forward-compatible event: durable inbox records that it was observed */ }
        }
        assertClaimOwner(event.eventId(), claimToken);
        jdbc.update("insert into integration.inbox_event(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) values (?,?,?,?,?,'PROCESSED') on conflict (consumer_name,event_id) do nothing", CONSUMER, event.eventId(), event.tenantId(), event.workspaceId(), Timestamp.from(Instant.now()));
    }

    private void processPushDelivery(EventRow event, UUID claimToken) {
        assertClaimOwner(event.eventId(), claimToken);
        Map<String, Object> payload = payload(event.payload());
        NotificationProjection projection = new NotificationProjection(
                string(payload.get("sourceEventId")),
                string(payload.get("tenantId")),
                string(payload.get("workspaceId")),
                string(payload.get("clientAccountId")),
                string(payload.get("aggregateType")),
                string(payload.get("aggregateId")),
                string(payload.get("eventType")),
                string(payload.get("publicStatus")),
                Instant.parse(string(payload.get("occurredAt"))),
                strings(payload.get("recipientMembershipIds")));
        notifications.deliverPush(new PushNotificationCandidate(projection,
                string(payload.get("category")), string(payload.get("title")),
                string(payload.get("message")), string(payload.get("deepLink"))));
    }

    private void finalizePublished(EventRow event, UUID claimToken) {
        assertClaimOwner(event.eventId(), claimToken);
        int finalized = jdbc.update("update integration.outbox_event set status='PUBLISHED',processed_at=current_timestamp,next_attempt_at=current_timestamp,processing_started_at=null,lease_until=null,claim_token=null where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp", event.eventId(), claimToken);
        if (finalized != 1) throw new ClaimLostException();
    }

    private void assertClaimOwner(UUID eventId, UUID claimToken) {
        Boolean owner = jdbc.queryForObject("select exists(select 1 from integration.outbox_event where event_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp)", Boolean.class, eventId, claimToken);
        if (!Boolean.TRUE.equals(owner)) throw new ClaimLostException();
    }

    private void registerGauges() {
        if (metrics == null) return;
        metrics.gauge("outbox", "pending", () -> queryDouble("select count(*) from integration.outbox_event where status in ('PENDING','FAILED')"));
        metrics.gauge("outbox", "oldest_pending_age_seconds", () -> queryDouble("select coalesce(extract(epoch from current_timestamp - min(created_at)),0) from integration.outbox_event where status in ('PENDING','FAILED')"));
        metrics.gauge("outbox", "failed", () -> queryDouble("select count(*) from integration.outbox_event where status='FAILED'"));
        metrics.gauge("outbox", "dead_letter", () -> queryDouble("select count(*) from integration.outbox_event where status='DEAD_LETTER'"));
        metrics.gauge("outbox", "processing_age_seconds", () -> queryDouble("select coalesce(extract(epoch from current_timestamp - min(processing_started_at)),0) from integration.outbox_event where status='PROCESSING'"));
    }

    private double queryDouble(String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private TechnicalMetrics.TimerSample start(String operation) { return metrics == null ? null : metrics.start("outbox", operation); }
    private void record(TechnicalMetrics.TimerSample timer, String outcome) { if (metrics != null && timer != null) timer.stop(outcome); }
    private void count(String operation, String outcome) { if (metrics != null) metrics.count("outbox", operation, outcome); }

    private void convertApproved(EventRow event, Map<String, Object> payload) {
        UUID requestId = uuid(payload.getOrDefault("purchaseRequestId", event.aggregateId()));
        if (salesContext.findSalesOrderBySourcePurchaseRequest(event.tenantId(), event.workspaceId(), requestId).isPresent()) return;
        long version = number(payload.get("purchaseRequestVersion"), salesContext
                .findPurchaseRequest(event.tenantId(), event.workspaceId(), requestId)
                .orElseThrow(() -> new IllegalStateException("Purchase request context is unavailable"))
                .version());
        CurrentAccessContext context = actor(event);
        try {
            salesOrders.convert(context, requestId.toString(), version, "outbox-conversion-" + event.eventId(), "Automatic conversion after purchase request approval");
        } catch (CommercialBusinessException exception) {
            if ("PURCHASE_REQUEST_EXPIRED".equals(exception.code())) {
                LOG.info("Purchase request expired before automatic conversion; publishing approval event as terminal no-op requestId={}", requestId);
                return;
            }
            throw exception;
        }
    }

    private void reserveConfirmed(EventRow event, Map<String, Object> payload) {
        UUID orderId = uuid(payload.getOrDefault("salesOrderId", event.aggregateId()));
        // v0.15 canonical orders reserve through BC-05 commercial backing and
        // physical allocation. The legacy outbox reservation must not create a
        // second reservation for the same Sales Order.
        SalesEventContextQueryPort.SalesOrderSnapshot snapshot = salesContext
                .findSalesOrder(event.tenantId(), event.workspaceId(), orderId)
                .orElseThrow(() -> new IllegalStateException("Sales order context is unavailable"));
        if (snapshot.commercialCommitmentId() != null) return;
        // A confirmed order may already have been reserved and consumed by a
        // synchronous warehouse/logistics operation while this outbox event
        // was waiting. Any reservation for the order therefore makes this
        // event replay-safe; checking only active rows could create a second
        // reservation after route start.
        if (warehouseContext.findReservationForSalesOrder(event.tenantId(), event.workspaceId(), orderId).isPresent()) return;
        long version = number(payload.get("salesOrderVersion"), snapshot.version());
        warehouse.reserve(actor(event), orderId.toString(), version, "outbox-reservation-" + event.eventId(), event.correlationId());
    }

    private void createReceivable(EventRow event, Map<String, Object> payload) {
        UUID orderId = uuid(payload.getOrDefault("salesOrderId", event.aggregateId()));
        CurrentAccessContext context = actor(event);
        payments.createReceivable(context, new PaymentPort.ReceivableCommand(
                "SALES_ORDER", orderId, null, "outbox-receivable-" + event.eventId()));
    }

    private void createDispatch(EventRow event, Map<String, Object> payload) {
        UUID reservationId = uuid(payload.getOrDefault("reservationId", event.aggregateId()));
        if (logisticsContext.findDispatchByReservation(event.tenantId(), event.workspaceId(), reservationId).isPresent()) return;
        long version = number(payload.get("reservationVersion"), warehouseContext
                .findReservation(event.tenantId(), event.workspaceId(), reservationId)
                .orElseThrow(() -> new IllegalStateException("Inventory reservation context is unavailable"))
                .version());
        logistics.create(actor(event), reservationId.toString(), version, "outbox-dispatch-" + event.eventId());
    }

    private void generateDeliveryDocuments(EventRow event, Map<String, Object> payload) {
        CurrentAccessContext context = actor(event);
        UUID dispatchId = uuid(payload.getOrDefault("dispatchOrderId", event.aggregateId()));
        requestDocument(context, event, "DISPATCH_ORDER", dispatchId, "DELIVERY_GUIDE_DRAFT", "PDF", "delivery-guide");
        UUID podId = payload.get("podId") == null ? null : uuid(payload.get("podId"));
        if (podId != null) requestDocument(context, event, "PROOF_OF_DELIVERY", podId, "POD_REPORT", "PDF", "pod-report");
        UUID orderId = payload.get("salesOrderId") == null ? null : uuid(payload.get("salesOrderId"));
        if (orderId != null) {
            requestDocument(context, event, "SALES_ORDER", orderId, "ORDER_SUMMARY", "PDF", "order-summary-pdf");
            requestDocument(context, event, "SALES_ORDER", orderId, "ORDER_SUMMARY", "CSV", "order-summary-csv");
        }
    }

    private void requestDocument(CurrentAccessContext context, EventRow event, String subjectType, UUID subjectId,
                                 String documentType, String format, String suffix) {
        documents.request(context, subjectType, subjectId, documentType, format,
                "outbox-" + event.eventId() + "-" + suffix);
    }

    private void projectNotification(EventRow event, Map<String, Object> payload) {
        String clientAccountId = clientAccount(event, payload);
        Set<String> recipientIds = new HashSet<>();
        recipientIds.addAll(tenantContext.findActiveMembershipIdsByRoleCodes(event.tenantId(), event.workspaceId(),
                        Set.of("sales", "company_owner", "tenant_admin"))
                .stream().map(UUID::toString).toList());
        if (clientAccountId != null && Set.of("DISPATCH_DELIVERED", "PAYMENT_SUCCEEDED").contains(event.eventType())) {
            recipientIds.addAll(salesContext.findBuyerMembershipIds(event.tenantId(), event.workspaceId(),
                            UUID.fromString(clientAccountId)).stream().map(UUID::toString).toList());
        }
        if (!recipientIds.isEmpty()) notifications.project(new NotificationProjection(event.eventId().toString(), event.tenantId().toString(), event.workspaceId().toString(), clientAccountId, event.aggregateType(), event.aggregateId().toString(), event.eventType(), string(payload.get("status")), event.occurredAt(), recipientIds));
    }

    private String clientAccount(EventRow event, Map<String, Object> payload) {
        Object explicit = payload.get("clientAccountId");
        if (explicit != null) return explicit.toString();
        return switch (event.aggregateType()) {
            case "PurchaseRequest" -> salesContext.findPurchaseRequest(event.tenantId(), event.workspaceId(), event.aggregateId())
                    .map(SalesEventContextQueryPort.PurchaseRequestSnapshot::clientAccountId).map(UUID::toString).orElse(null);
            case "SalesOrder" -> salesContext.findSalesOrder(event.tenantId(), event.workspaceId(), event.aggregateId())
                    .map(SalesEventContextQueryPort.SalesOrderSnapshot::clientAccountId).map(UUID::toString).orElse(null);
            case "DispatchOrder" -> logisticsContext.findDispatch(event.tenantId(), event.workspaceId(), event.aggregateId())
                    .map(LogisticsEventContextQueryPort.DispatchSnapshot::clientAccountId).map(UUID::toString).orElse(null);
            case "ProofOfDelivery" -> {
                Object dispatchId = payload.get("dispatchOrderId");
                yield dispatchId == null ? null : logisticsContext.findDispatch(event.tenantId(), event.workspaceId(), uuid(dispatchId))
                        .map(LogisticsEventContextQueryPort.DispatchSnapshot::clientAccountId).map(UUID::toString).orElse(null);
            }
            case "Receivable", "Payment" -> paymentContext.findClientAccountId(event.tenantId(), event.workspaceId(),
                    event.aggregateType(), event.aggregateId()).map(UUID::toString).orElse(null);
            default -> null;
        };
    }

    private CurrentAccessContext actor(EventRow event) {
        TenantEventContextQueryPort.WorkflowActor principal = tenantContext.findSystemWorkflowActor(event.tenantId(), event.workspaceId());
        CurrentAccessContext context = access.resolve(new CurrentAccessRequest(new UserId(principal.userId()),
                new TenantId(event.tenantId()), new WorkspaceId(event.workspaceId()), Surface.PLATFORM));
        if (!principal.membershipId().equals(context.membershipId().value())
                || !principal.userId().equals(context.userId().value())
                || !context.hasRoleCode(TenantEventContextQueryPort.SYSTEM_WORKFLOW_ROLE_CODE)) {
            throw new IllegalStateException("Resolved workflow actor is not the explicit SYSTEM_WORKFLOW/NEXA_AUTOMATION principal");
        }
        return context;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(String value) {
        try { return mapper.readValue(value == null ? "{}" : value, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalArgumentException("Canonical outbox payload is invalid", exception); }
    }
    private static UUID uuid(Object value) { if (value instanceof UUID id) return id; return UUID.fromString(String.valueOf(value)); }
    private static long number(Object value, long fallback) { return value instanceof Number number ? number.longValue() : value == null ? fallback : Long.parseLong(String.valueOf(value)); }
    private static String string(Object value) { return value == null ? null : String.valueOf(value); }

    private static Set<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return Set.of();
        Set<String> result = new HashSet<>();
        for (Object item : values) if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item));
        return Set.copyOf(result);
    }

    private record EventRow(UUID eventId, String eventType, String aggregateType, UUID aggregateId, UUID tenantId,
                            UUID workspaceId, Instant occurredAt, String correlationId, UUID causationId,
                            String schemaVersion, String payload, int attemptCount) { }

    private static final class ClaimLostException extends RuntimeException {
        private ClaimLostException() { super("Outbox claim is no longer owned by this worker"); }
    }
}
