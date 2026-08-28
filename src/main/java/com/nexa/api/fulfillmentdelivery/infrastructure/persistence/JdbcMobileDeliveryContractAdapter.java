package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.MobileDeliveryContractPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** SQL adapter for G03/G04; raw handoff tokens never reach this adapter. */
@Repository
@Profile("!test")
public class JdbcMobileDeliveryContractAdapter implements MobileDeliveryContractPort {
    private final JdbcTemplate jdbc;

    public JdbcMobileDeliveryContractAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public HandoffIssue issue(IssueRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.deliveryId(), request.attemptId(),
                request.actorMembershipId(), request.idempotencyKey(), request.requestHash(), request.tokenHash(),
                request.issuedAt(), request.expiresAt());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "HANDOFF_ISSUE", request.idempotencyKey());
        HandoffRow prior = jdbc.query("select id,delivery_id,delivery_attempt_id,expires_at,status from logistics.delivery_handoff_token "
                        + "where tenant_id=? and workspace_id=? and issuer_membership_id=? and idempotency_key=? for update",
                (rs, row) -> new HandoffRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getTimestamp("expires_at").toInstant(), rs.getString("status")),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey())
                .stream().findFirst().orElse(null);
        if (prior != null) {
            ensureRequestHash(request, prior.id());
            return new HandoffIssue(prior.id(), prior.deliveryId(), prior.attemptId(), prior.expiresAt(), prior.status(), true);
        }
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery == null) throw error("DELIVERY_NOT_FOUND", true);
        if (!isActiveDelivery(delivery.status())) throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
        if (!assignedDriver(request.tenantId(), request.workspaceId(), request.deliveryId(), request.actorMembershipId(), request.actorUserId())) {
            throw error("DELIVERY_HANDOFF_DRIVER_NOT_ASSIGNED", false);
        }
        AttemptRow attempt = jdbc.query("select id,status from logistics.delivery_attempt where tenant_id=? and workspace_id=? and id=? and delivery_id=? for update",
                (rs, row) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getString("status")),
                request.tenantId(), request.workspaceId(), request.attemptId(), request.deliveryId())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_ATTEMPT_NOT_FOUND", true));
        if (isTerminalAttempt(attempt.status())) throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
        UUID handoffId = UUID.randomUUID();
        jdbc.update("update logistics.delivery_handoff_token set status='REPLACED' where tenant_id=? and workspace_id=? and delivery_id=? and delivery_attempt_id=? and status='ACTIVE'",
                request.tenantId(), request.workspaceId(), request.deliveryId(), request.attemptId());
        UUID customerAccountId = customerAccount(request.tenantId(), request.workspaceId(), delivery);
        jdbc.update("insert into logistics.delivery_handoff_token(id,tenant_id,workspace_id,delivery_id,delivery_attempt_id,customer_account_id,token_hash,issued_at,expires_at,issuer_membership_id,idempotency_key,request_hash,status,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                handoffId, request.tenantId(), request.workspaceId(), request.deliveryId(), request.attemptId(), customerAccountId,
                request.tokenHash(), Timestamp.from(request.issuedAt()), Timestamp.from(request.expiresAt()), request.actorMembershipId(),
                request.idempotencyKey(), request.requestHash(), "ACTIVE", Timestamp.from(request.issuedAt()));
        return new HandoffIssue(handoffId, request.deliveryId(), request.attemptId(), request.expiresAt(), "ACTIVE", false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public HandoffValidation validate(ValidationRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.buyerMembershipId(), request.customerAccountId(), request.tokenHash(), request.now());
        return jdbc.query("select h.id,h.delivery_id,h.delivery_attempt_id,h.expires_at,h.status handoff_status,d.status delivery_status,a.status attempt_status,coalesce(sum(coalesce(l.received_quantity,0)),0) delivered_quantity "
                        + "from logistics.delivery_handoff_token h join logistics.delivery d on d.tenant_id=h.tenant_id and d.workspace_id=h.workspace_id and d.id=h.delivery_id "
                        + "join logistics.delivery_attempt a on a.tenant_id=h.tenant_id and a.workspace_id=h.workspace_id and a.id=h.delivery_attempt_id and a.delivery_id=h.delivery_id "
                        + "left join logistics.delivery_attempt_line l on l.tenant_id=a.tenant_id and l.workspace_id=a.workspace_id and l.delivery_attempt_id=a.id "
                        + "where h.tenant_id=? and h.workspace_id=? and h.token_hash=? and h.customer_account_id=? "
                        + "group by h.id,h.delivery_id,h.delivery_attempt_id,h.expires_at,h.status,d.status,a.status",
                (rs, row) -> new ValidationRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("handoff_status"), rs.getString("delivery_status"), rs.getString("attempt_status"),
                        rs.getBigDecimal("delivered_quantity")),
                request.tenantId(), request.workspaceId(), request.tokenHash(), request.customerAccountId())
                .stream().findFirst().map(row -> {
                    if (!row.expiresAt().isAfter(request.now())) throw error("DELIVERY_HANDOFF_EXPIRED", false);
                    if (!"ACTIVE".equals(row.handoffStatus())) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
                    if (!isActiveDelivery(row.deliveryStatus()) || isTerminalAttempt(row.attemptStatus())) {
                        throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
                    }
                    return new HandoffValidation(row.handoffId(), row.deliveryId(), row.attemptId(), row.expiresAt(), row.deliveryStatus(), row.deliveredQuantity());
                }).orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BuyerReceipt recordReceipt(ReceiptRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.deliveryId(), request.buyerMembershipId(),
                request.customerAccountId(), request.tokenHash(), request.decision(), request.acceptedQuantity(),
                request.idempotencyKey(), request.requestHash(), request.now());
        lockCommand(request.tenantId(), request.workspaceId(), request.buyerMembershipId(), "BUYER_RECEIPT", request.idempotencyKey());
        BuyerReceiptRow prior = jdbc.query("select id,delivery_id,delivery_attempt_id,decision,driver_delivered_quantity,accepted_quantity,reason,occurred_at from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and buyer_membership_id=? and idempotency_key=? for update",
                (rs, row) -> new BuyerReceiptRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getString("decision"), rs.getBigDecimal("driver_delivered_quantity"),
                        rs.getBigDecimal("accepted_quantity"), rs.getString("reason"), rs.getTimestamp("occurred_at").toInstant()),
                request.tenantId(), request.workspaceId(), request.buyerMembershipId(), request.idempotencyKey()).stream().findFirst().orElse(null);
        if (prior != null) {
            String storedHash = jdbc.queryForObject("select request_hash from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and id=?",
                    String.class, request.tenantId(), request.workspaceId(), prior.id());
            if (!Objects.equals(storedHash, request.requestHash())) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            return prior.toResult(true);
        }
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery == null) throw error("DELIVERY_NOT_FOUND", true);
        HandoffRow handoff = jdbc.query("select id,delivery_attempt_id,customer_account_id,expires_at,status from logistics.delivery_handoff_token where tenant_id=? and workspace_id=? and delivery_id=? and token_hash=? for update",
                (rs, row) -> new HandoffRow(rs.getObject("id", UUID.class), request.deliveryId(), rs.getObject("delivery_attempt_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("status")), request.tenantId(), request.workspaceId(), request.deliveryId(), request.tokenHash())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
        UUID customer = jdbc.queryForObject("select customer_account_id from logistics.delivery_handoff_token where tenant_id=? and workspace_id=? and id=?", UUID.class,
                request.tenantId(), request.workspaceId(), handoff.id());
        if (!request.customerAccountId().equals(customer)) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        if (jdbc.queryForObject("select count(*) from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and delivery_attempt_id=?", Integer.class,
                request.tenantId(), request.workspaceId(), handoff.attemptId()) > 0) throw error("BUYER_RECEIPT_ALREADY_RECORDED", false);
        if (!isActiveDelivery(delivery.status())) throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
        if (!handoff.expiresAt().isAfter(request.now())) throw error("DELIVERY_HANDOFF_EXPIRED", false);
        if (!"ACTIVE".equals(handoff.status())) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        AttemptRow attempt = jdbc.query("select id,status from logistics.delivery_attempt where tenant_id=? and workspace_id=? and id=? and delivery_id=? for update",
                (rs, row) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getString("status")), request.tenantId(), request.workspaceId(), handoff.attemptId(), request.deliveryId())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_ATTEMPT_NOT_FOUND", true));
        if (isTerminalAttempt(attempt.status())) throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
        BigDecimal delivered = jdbc.queryForObject("select coalesce(sum(coalesce(received_quantity,0)),0) from logistics.delivery_attempt_line where tenant_id=? and workspace_id=? and delivery_attempt_id=?",
                BigDecimal.class, request.tenantId(), request.workspaceId(), handoff.attemptId());
        if (request.acceptedQuantity().compareTo(delivered) > 0
                || ("ACCEPTED".equals(request.decision()) && request.acceptedQuantity().compareTo(delivered) != 0)) {
            throw error("BUYER_RECEIPT_QUANTITY_INVALID", false);
        }
        UUID receiptId = UUID.randomUUID();
        jdbc.update("insert into logistics.buyer_receipt_fact(id,tenant_id,workspace_id,delivery_id,delivery_attempt_id,customer_account_id,buyer_membership_id,handoff_token_id,decision,driver_delivered_quantity,accepted_quantity,reason,occurred_at,idempotency_key,request_hash) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                receiptId, request.tenantId(), request.workspaceId(), request.deliveryId(), handoff.attemptId(), request.customerAccountId(),
                request.buyerMembershipId(), handoff.id(), request.decision(), delivered, request.acceptedQuantity(), request.reason(),
                Timestamp.from(request.now()), request.idempotencyKey(), request.requestHash());
        if (jdbc.update("update logistics.delivery_handoff_token set status='CONSUMED' where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                request.tenantId(), request.workspaceId(), handoff.id()) != 1) throw error("CONCURRENCY_CONFLICT", false);
        return new BuyerReceipt(receiptId, request.deliveryId(), handoff.attemptId(), request.decision(), delivered,
                request.acceptedQuantity(), request.reason(), request.now(), false);
    }

    private DeliveryRow lockDelivery(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query("select d.id,d.fulfillment_id,d.dispatch_order_id,d.status from logistics.delivery d where d.tenant_id=? and d.workspace_id=? and d.id=? for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("dispatch_order_id", UUID.class), rs.getString("status")),
                tenant, workspace, id).stream().findFirst().orElse(null);
    }

    private UUID customerAccount(UUID tenant, UUID workspace, DeliveryRow delivery) {
        return jdbc.query("select coalesce(so.client_account_id, dispatch.client_account_id) customer_account_id "
                        + "from logistics.delivery d "
                        + "left join logistics.fulfillment f on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "left join sales.sales_order so on so.tenant_id=f.tenant_id and so.workspace_id=f.workspace_id and so.id=f.sales_order_id "
                        + "left join logistics.dispatch_order dispatch on dispatch.tenant_id=d.tenant_id and dispatch.workspace_id=d.workspace_id and dispatch.id=d.dispatch_order_id "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? "
                        + "and coalesce(so.client_account_id, dispatch.client_account_id) is not null",
                (rs, row) -> rs.getObject("customer_account_id", UUID.class), tenant, workspace, delivery.id())
                .stream().findFirst().orElseThrow(() -> error("BUYER_RELATIONSHIP_NOT_FOUND", false));
    }

    private boolean assignedDriver(UUID tenant, UUID workspace, UUID delivery, UUID membership, UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from logistics.delivery_assignment a where a.tenant_id=? and a.workspace_id=? and a.delivery_id=? and (a.responsible_membership_id=? or a.operator_id=?)) or exists(select 1 from logistics.delivery d join logistics.dispatch_order o on o.tenant_id=d.tenant_id and o.workspace_id=d.workspace_id and o.id=d.dispatch_order_id where d.tenant_id=? and d.workspace_id=? and d.id=? and o.responsible_membership_id=?)",
                Boolean.class, tenant, workspace, delivery, membership, user, tenant, workspace, delivery, membership));
    }

    private void ensureRequestHash(IssueRequest request, UUID handoffId) {
        String stored = jdbc.queryForObject("select request_hash from logistics.delivery_handoff_token where tenant_id=? and workspace_id=? and id=?",
                String.class, request.tenantId(), request.workspaceId(), handoffId);
        if (!Objects.equals(stored, request.requestHash())) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
    }

    private void lockCommand(UUID tenant, UUID workspace, UUID actor, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|mobile-delivery|" + actor + "|" + operation + "|" + key);
    }

    private static void requireScope(Object... values) {
        for (Object value : values) if (value == null) throw new IllegalArgumentException("Mobile delivery request is incomplete");
    }

    private static void ensureHash(String stored, String actual) {
        if (!Objects.equals(stored, actual)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
    }

    private static boolean isActiveDelivery(String status) {
        return "ASSIGNED".equals(status) || "DISPATCHED".equals(status)
                || "IN_TRANSIT".equals(status) || "PARTIAL".equals(status);
    }
    private static boolean isTerminalAttempt(String status) { return "FINAL".equals(status) || "FAILED".equals(status) || "REJECTED".equals(status); }
    private static FulfillmentOperationException error(String code, boolean notFound) { return new FulfillmentOperationException(code, notFound); }

    private record DeliveryRow(UUID id, UUID fulfillmentId, UUID dispatchOrderId, String status) { }
    private record AttemptRow(UUID id, String status) { }
    private record HandoffRow(UUID id, UUID deliveryId, UUID attemptId, Instant expiresAt, String status) { }
    private record ValidationRow(UUID handoffId, UUID deliveryId, UUID attemptId, Instant expiresAt,
                                 String handoffStatus, String deliveryStatus, String attemptStatus,
                                 BigDecimal deliveredQuantity) { }
    private record BuyerReceiptRow(UUID id, UUID deliveryId, UUID attemptId, String decision, BigDecimal delivered,
                                   BigDecimal accepted, String reason, Instant occurredAt) {
        private BuyerReceipt toResult(boolean replayed) { return new BuyerReceipt(id, deliveryId, attemptId, decision, delivered, accepted, reason, occurredAt, replayed); }
    }
}
