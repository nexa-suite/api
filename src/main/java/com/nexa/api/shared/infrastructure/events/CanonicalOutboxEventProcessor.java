package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.invoicing.application.port.BusinessDocumentPort;
import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationProjection;
import com.nexa.api.notifications.application.port.in.NotificationProjectionPort;
import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.sales.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.sales.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.shared.infrastructure.security.RlsRequestScope;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.warehouse.application.WarehouseOperationsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final NotificationProjectionPort notifications;
    private final ResolveCurrentAccessContextUseCase access;
    private final PurchaseRequestUseCase purchaseRequests;
    private final SalesOrderUseCase salesOrders;
    private final WarehouseOperationsService warehouse;
    private final LogisticsOperationsService logistics;
    private final BusinessDocumentPort documents;

    public CanonicalOutboxEventProcessor(JdbcTemplate jdbc, ObjectMapper mapper,
                                         @Qualifier("notificationProjectionPort") NotificationProjectionPort notifications,
                                         ResolveCurrentAccessContextUseCase access,
                                         PurchaseRequestUseCase purchaseRequests,
                                         SalesOrderUseCase salesOrders,
                                         WarehouseOperationsService warehouse,
                                         LogisticsOperationsService logistics,
                                         BusinessDocumentPort documents) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.notifications = notifications;
        this.access = access;
        this.purchaseRequests = purchaseRequests;
        this.salesOrders = salesOrders;
        this.warehouse = warehouse;
        this.logistics = logistics;
        this.documents = documents;
    }

    @Scheduled(fixedDelayString = "${nexa.integration.outbox-delay-ms:1000}")
    public void processBatch() {
        jdbc.update("update integration.outbox_event set status='PENDING',next_attempt_at=current_timestamp where status='PROCESSING' and attempt_count > 0 and created_at < current_timestamp - interval '10 minutes'");
        List<EventRow> rows = jdbc.query("select event_id,event_type,aggregate_type,aggregate_id,tenant_id,workspace_id,occurred_at,correlation_id,causation_id,schema_version,payload::text,attempt_count from integration.outbox_event where status in ('PENDING','FAILED') and next_attempt_at <= current_timestamp and attempt_count < 20 order by created_at,event_id limit 20", (rs, n) -> new EventRow(
                rs.getObject("event_id", UUID.class), rs.getString("event_type"), rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("correlation_id"), rs.getObject("causation_id", UUID.class), rs.getString("schema_version"),
                rs.getString("payload"), rs.getInt("attempt_count")));
        for (EventRow row : rows) {
            if (jdbc.update("update integration.outbox_event set status='PROCESSING',attempt_count=attempt_count+1 where event_id=? and status in ('PENDING','FAILED') and attempt_count < 20", row.eventId()) != 1) continue;
            try {
                processOne(row);
                jdbc.update("update integration.outbox_event set status='PUBLISHED',processed_at=current_timestamp,next_attempt_at=current_timestamp where event_id=? and status='PROCESSING'", row.eventId());
            } catch (RuntimeException exception) {
                LOG.error("Canonical outbox processing failed eventId={} eventType={} correlationId={}", row.eventId(), row.eventType(), row.correlationId(), exception);
                jdbc.update("update integration.outbox_event set status=case when attempt_count >= 20 then 'DEAD_LETTER' else 'FAILED' end,next_attempt_at=current_timestamp + (least(power(2,attempt_count),300) * interval '1 second') where event_id=? and status='PROCESSING'", row.eventId());
            }
        }
    }

    private void processOne(EventRow event) {
        if (jdbc.queryForObject("select exists(select 1 from integration.inbox_event where consumer_name=? and event_id=?)", Boolean.class, CONSUMER, event.eventId())) return;
        Map<String, Object> payload = payload(event.payload());
        RlsRequestScope.set(event.tenantId(), event.workspaceId());
        try {
            switch (event.eventType()) {
                case "PURCHASE_REQUEST_SUBMITTED", "PURCHASE_REQUEST_APPROVED", "SALES_ORDER_CONFIRMED", "DISPATCH_DELIVERED", "DELIVERY_COMPLETED", "POD_COMPLETED", "PAYMENT_SUCCEEDED" -> {
                    projectNotification(event, payload);
                    if ("PURCHASE_REQUEST_APPROVED".equals(event.eventType())) convertApproved(event, payload);
                    if ("SALES_ORDER_CONFIRMED".equals(event.eventType())) reserveConfirmed(event, payload);
                    if ("DISPATCH_DELIVERED".equals(event.eventType())) generateDeliveryDocuments(event, payload);
                }
                case "FULFILLMENT_READY" -> createDispatch(event, payload);
                case "INVOICE_ISSUED", "BUSINESS_DOCUMENT_GENERATION_REQUESTED" -> { /* durable facts; source service owns the effect */ }
                default -> { /* forward-compatible event: durable inbox records that it was observed */ }
            }
            jdbc.update("insert into integration.inbox_event(consumer_name,event_id,tenant_id,workspace_id,processed_at,result) values (?,?,?,?,?,'PROCESSED') on conflict (consumer_name,event_id) do nothing", CONSUMER, event.eventId(), event.tenantId(), event.workspaceId(), Timestamp.from(Instant.now()));
        } finally {
            RlsRequestScope.clear();
        }
    }

    private void convertApproved(EventRow event, Map<String, Object> payload) {
        UUID requestId = uuid(payload.getOrDefault("purchaseRequestId", event.aggregateId()));
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.sales_order where tenant_id=? and workspace_id=? and source_purchase_request_id=?)", Boolean.class, event.tenantId(), event.workspaceId(), requestId))) return;
        long version = number(payload.get("purchaseRequestVersion"), jdbc.queryForObject("select version from sales.purchase_request where tenant_id=? and workspace_id=? and id=?", Long.class, event.tenantId(), event.workspaceId(), requestId));
        CurrentAccessContext context = actor(event, "sales");
        salesOrders.convert(context, requestId.toString(), version, "outbox-conversion-" + event.eventId(), "Automatic conversion after purchase request approval");
    }

    private void reserveConfirmed(EventRow event, Map<String, Object> payload) {
        UUID orderId = uuid(payload.getOrDefault("salesOrderId", event.aggregateId()));
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from warehouse.inventory_reservation where tenant_id=? and workspace_id=? and sales_order_id=? and status in ('PENDING','RESERVED'))", Boolean.class, event.tenantId(), event.workspaceId(), orderId))) return;
        long version = number(payload.get("salesOrderVersion"), jdbc.queryForObject("select version from sales.sales_order where tenant_id=? and workspace_id=? and id=?", Long.class, event.tenantId(), event.workspaceId(), orderId));
        warehouse.reserve(actor(event, "warehouse"), orderId.toString(), version, "outbox-reservation-" + event.eventId(), event.correlationId());
    }

    private void createDispatch(EventRow event, Map<String, Object> payload) {
        UUID reservationId = uuid(payload.getOrDefault("reservationId", event.aggregateId()));
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.dispatch_order where tenant_id=? and workspace_id=? and inventory_reservation_id=?)", Boolean.class, event.tenantId(), event.workspaceId(), reservationId))) return;
        long version = number(payload.get("reservationVersion"), jdbc.queryForObject("select version from warehouse.inventory_reservation where tenant_id=? and workspace_id=? and id=?", Long.class, event.tenantId(), event.workspaceId(), reservationId));
        logistics.create(actor(event, "logistics"), reservationId.toString(), version, "outbox-dispatch-" + event.eventId());
    }

    private void generateDeliveryDocuments(EventRow event, Map<String, Object> payload) {
        CurrentAccessContext context = actor(event, "company_owner");
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
        recipientIds.addAll(memberships(event, Set.of("sales", "company_owner", "tenant_admin"), null));
        if (clientAccountId != null && Set.of("DISPATCH_DELIVERED", "PAYMENT_SUCCEEDED").contains(event.eventType())) {
            recipientIds.addAll(memberships(event, Set.of(), clientAccountId));
        }
        if (!recipientIds.isEmpty()) notifications.project(new NotificationProjection(event.eventId().toString(), event.tenantId().toString(), event.workspaceId().toString(), clientAccountId, event.aggregateType(), event.aggregateId().toString(), event.eventType(), string(payload.get("status")), event.occurredAt(), recipientIds));
    }

    private Set<String> memberships(EventRow event, Set<String> roles, String clientAccountId) {
        String roleClause = roles.isEmpty() ? "" : " and lower(r.code) in (" + String.join(",", roles.stream().map(ignored -> "?").toList()) + ")";
        List<Object> args = new java.util.ArrayList<>(List.of(event.tenantId(), event.workspaceId()));
        if (clientAccountId != null) args.add(UUID.fromString(clientAccountId));
        args.addAll(roles);
        String sql = "select distinct m.id from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id "
                + "join tenant_management.membership_role_definition mr on mr.membership_id=m.id and mr.tenant_id=? and mr.workspace_id=? "
                + "join tenant_management.role_definition r on r.id=mr.role_id where w.tenant_id=? and m.workspace_id=? and m.status='ACTIVE'";
        // Keep scope values explicit in every join; the first two placeholders belong to mr.
        args = new java.util.ArrayList<>(List.of(event.tenantId(), event.workspaceId(), event.tenantId(), event.workspaceId()));
        if (clientAccountId != null) {
            sql += " and exists(select 1 from sales.client_account_membership cam where cam.tenant_id=? and cam.workspace_id=? and cam.client_account_id=? and cam.workspace_membership_id=m.id)";
            args.add(event.tenantId()); args.add(event.workspaceId()); args.add(UUID.fromString(clientAccountId));
        }
        sql += roleClause;
        args.addAll(roles);
        return new HashSet<>(jdbc.query(sql, (rs, n) -> rs.getObject(1).toString(), args.toArray()));
    }

    private String clientAccount(EventRow event, Map<String, Object> payload) {
        Object explicit = payload.get("clientAccountId");
        if (explicit != null) return explicit.toString();
        String table = switch (event.aggregateType()) {
            case "PurchaseRequest" -> "sales.purchase_request";
            case "SalesOrder" -> "sales.sales_order";
            case "DispatchOrder" -> "logistics.dispatch_order";
            case "Receivable", "Payment" -> "payments." + ("Receivable".equals(event.aggregateType()) ? "receivable" : "payment");
            default -> null;
        };
        if (table == null) return null;
        return jdbc.query("select client_account_id from " + table + " where tenant_id=? and workspace_id=? and id=?", rs -> rs.next() && rs.getObject(1) != null ? rs.getObject(1).toString() : null, event.tenantId(), event.workspaceId(), event.aggregateId());
    }

    private CurrentAccessContext actor(EventRow event, String role) {
        UUID userId = jdbc.queryForObject("select m.user_id from tenant_management.workspace_membership m join tenant_management.membership_role_definition mr on mr.membership_id=m.id and mr.tenant_id=? and mr.workspace_id=? join tenant_management.role_definition r on r.id=mr.role_id where m.workspace_id=? and m.status='ACTIVE' and lower(r.code)=? order by m.id limit 1", UUID.class, event.tenantId(), event.workspaceId(), event.workspaceId(), role);
        CurrentAccessContext context = access.resolve(new CurrentAccessRequest(new UserId(userId.toString()), new TenantId(event.tenantId().toString()), new WorkspaceId(event.workspaceId().toString()), Surface.PLATFORM));
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

    private record EventRow(UUID eventId, String eventType, String aggregateType, UUID aggregateId, UUID tenantId,
                            UUID workspaceId, Instant occurredAt, String correlationId, UUID causationId,
                            String schemaVersion, String payload, int attemptCount) { }
}
