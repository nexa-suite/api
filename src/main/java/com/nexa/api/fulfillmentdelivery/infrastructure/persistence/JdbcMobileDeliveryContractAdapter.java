package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.MobileDeliveryContractPort;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** SQL adapter for G03/G04; raw handoff tokens never reach this adapter. */
@Repository
@Profile("!test")
public class JdbcMobileDeliveryContractAdapter implements MobileDeliveryContractPort {
    // V79 stored delivered quantity in quantity. V91 made the richer fields
    // nullable for historical rows; do not turn those rows into zero facts.
    private final JdbcTemplate jdbc;
    private final SalesOrderFulfillmentQuery salesOrders;
    private final CanonicalOutboxPort canonicalOutbox;

    public JdbcMobileDeliveryContractAdapter(JdbcTemplate jdbc, SalesOrderFulfillmentQuery salesOrders,
                                             CanonicalOutboxPort canonicalOutbox) {
        this.jdbc = jdbc;
        this.salesOrders = salesOrders;
        this.canonicalOutbox = Objects.requireNonNull(canonicalOutbox, "Tenant canonical outbox is required");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public HandoffIssue issue(IssueRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.deliveryId(), request.attemptId(),
                request.actorMembershipId(), request.idempotencyKey(), request.requestHash(), request.tokenHash(),
                request.issuedAt(), request.expiresAt());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "HANDOFF_ISSUE", request.idempotencyKey());
        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), request.deliveryId());
        if (delivery == null) throw error("DELIVERY_NOT_FOUND", true);
        HandoffRow prior = jdbc.query("select id,delivery_id,coalesce(delivery_attempt_id,delivery_active_attempt_id) delivery_attempt_id,expires_at,status from logistics.delivery_handoff_token "
                        + "where tenant_id=? and workspace_id=? and issuer_membership_id=? and idempotency_key=? for update",
                (rs, row) -> new HandoffRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getTimestamp("expires_at").toInstant(), rs.getString("status")),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey())
                .stream().findFirst().orElse(null);
        if (prior != null) {
            ensureRequestHash(request, prior.id());
            return new HandoffIssue(prior.id(), prior.deliveryId(), prior.attemptId(), prior.expiresAt(), prior.status(), true);
        }
        if (!isActiveDelivery(delivery.status())) throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
        if (!assignedDriver(request.tenantId(), request.workspaceId(), request.deliveryId(), request.actorMembershipId(), request.actorUserId())) {
            throw error("DELIVERY_HANDOFF_DRIVER_NOT_ASSIGNED", false);
        }
        UUID activeAttemptId = jdbc.query("select id from logistics.delivery_active_attempt where tenant_id=? and workspace_id=? and id=? and delivery_id=? for update",
                (rs, row) -> rs.getObject("id", UUID.class),
                request.tenantId(), request.workspaceId(), request.attemptId(), request.deliveryId())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_ATTEMPT_NOT_FOUND", true));
        UUID handoffId = UUID.randomUUID();
        jdbc.update("update logistics.delivery_handoff_token set status='REPLACED' where tenant_id=? and workspace_id=? and delivery_id=? and coalesce(delivery_attempt_id,delivery_active_attempt_id)=? and status='ACTIVE'",
                request.tenantId(), request.workspaceId(), request.deliveryId(), request.attemptId());
        UUID customerAccountId = customerAccount(request.tenantId(), request.workspaceId(), delivery);
        jdbc.update("insert into logistics.delivery_handoff_token(id,tenant_id,workspace_id,delivery_id,delivery_attempt_id,delivery_active_attempt_id,customer_account_id,token_hash,issued_at,expires_at,issuer_membership_id,idempotency_key,request_hash,status,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                handoffId, request.tenantId(), request.workspaceId(), request.deliveryId(), null, activeAttemptId, customerAccountId,
                request.tokenHash(), Timestamp.from(request.issuedAt()), Timestamp.from(request.expiresAt()), request.actorMembershipId(),
                request.idempotencyKey(), request.requestHash(), "ACTIVE", Timestamp.from(request.issuedAt()));
        return new HandoffIssue(handoffId, request.deliveryId(), request.attemptId(), request.expiresAt(), "ACTIVE", false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DispatchHandoffIssue issueDispatchHandoff(DispatchHandoffIssueRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.deliveryId(), request.assignmentId(),
                request.actorMembershipId(), request.idempotencyKey(), request.requestHash(), request.tokenHash(),
                request.issuedAt(), request.expiresAt());
        lockCommand(request.tenantId(), request.workspaceId(), request.actorMembershipId(),
                "HANDOFF_ISSUE", request.idempotencyKey());

        DispatchBinding binding = lockCurrentDispatchBinding(request.tenantId(), request.workspaceId(),
                request.deliveryId(), request.assignmentId());
        if (binding == null) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);

        DispatchHandoffRow prior = jdbc.query("select id,delivery_id,assignment_id,delivery_version,expires_at,status,request_hash "
                        + "from logistics.dispatch_handoff_identity where tenant_id=? and workspace_id=? "
                        + "and issuer_membership_id=? and idempotency_key=? for update",
                (rs, row) -> new DispatchHandoffRow(rs.getObject("id", UUID.class),
                        rs.getObject("delivery_id", UUID.class), rs.getObject("assignment_id", UUID.class),
                        rs.getLong("delivery_version"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status"), rs.getString("request_hash")),
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.idempotencyKey())
                .stream().findFirst().orElse(null);
        if (prior != null) {
            if (!Objects.equals(prior.requestHash(), request.requestHash())) {
                throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            }
            String status = prior.status();
            if ("ACTIVE".equals(status) && prior.deliveryVersion() != binding.deliveryVersion()) status = "REPLACED";
            else if ("ACTIVE".equals(status) && !prior.expiresAt().isAfter(request.issuedAt())) status = "EXPIRED";
            return new DispatchHandoffIssue(prior.id(), prior.deliveryId(), prior.assignmentId(),
                    prior.deliveryVersion(), prior.expiresAt(), status, true);
        }

        UUID handoffId = UUID.randomUUID();
        jdbc.update("update logistics.dispatch_handoff_identity set status='REPLACED' "
                        + "where tenant_id=? and workspace_id=? and assignment_id=? and status='ACTIVE'",
                request.tenantId(), request.workspaceId(), request.assignmentId());
        jdbc.update("insert into logistics.dispatch_handoff_identity(id,tenant_id,workspace_id,delivery_id,assignment_id,"
                        + "delivery_version,token_hash,issued_at,expires_at,issuer_membership_id,idempotency_key,"
                        + "request_hash,status,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                handoffId, request.tenantId(), request.workspaceId(), request.deliveryId(), request.assignmentId(),
                binding.deliveryVersion(), request.tokenHash(), Timestamp.from(request.issuedAt()),
                Timestamp.from(request.expiresAt()), request.actorMembershipId(), request.idempotencyKey(),
                request.requestHash(), "ACTIVE", Timestamp.from(request.issuedAt()));
        return new DispatchHandoffIssue(handoffId, request.deliveryId(), request.assignmentId(),
                binding.deliveryVersion(), request.expiresAt(), "ACTIVE", false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public HandoffValidation validate(ValidationRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.buyerMembershipId(), request.customerAccountId(), request.tokenHash(), request.now());
        return jdbc.query("select h.id,h.delivery_id,h.delivery_attempt_id,h.expires_at,h.status handoff_status,d.status delivery_status,a.status attempt_status,a.outcome attempt_outcome,coalesce(sum(coalesce(l.received_quantity, case when l.attempted_quantity is null then l.quantity else 0 end)),0) delivered_quantity "
                        + "from logistics.delivery_handoff_token h join logistics.delivery d on d.tenant_id=h.tenant_id and d.workspace_id=h.workspace_id and d.id=h.delivery_id "
                        + "join logistics.delivery_attempt a on a.tenant_id=h.tenant_id and a.workspace_id=h.workspace_id and a.id=h.delivery_attempt_id and a.delivery_id=h.delivery_id "
                        + "left join logistics.delivery_attempt_line l on l.tenant_id=a.tenant_id and l.workspace_id=a.workspace_id and l.delivery_attempt_id=a.id "
                        + "where h.tenant_id=? and h.workspace_id=? and h.token_hash=? and h.customer_account_id=? "
                        + "group by h.id,h.delivery_id,h.delivery_attempt_id,h.expires_at,h.status,d.status,a.status,a.outcome",
                (rs, row) -> new ValidationRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("handoff_status"), rs.getString("delivery_status"), rs.getString("attempt_status"),
                        rs.getString("attempt_outcome"), rs.getBigDecimal("delivered_quantity")),
                request.tenantId(), request.workspaceId(), request.tokenHash(), request.customerAccountId())
                .stream().findFirst().map(row -> {
                    if (!row.expiresAt().isAfter(request.now())) throw error("DELIVERY_HANDOFF_EXPIRED", false);
                    if (!"ACTIVE".equals(row.handoffStatus())) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
                    boolean activeAttempt = isActiveDelivery(row.deliveryStatus())
                            && !isTerminalAttempt(row.attemptStatus());
                    boolean completedAttempt = isSuccessfulTerminalAttempt(row.deliveryStatus(),
                            row.attemptStatus(), row.attemptOutcome());
                    if (!activeAttempt && !completedAttempt) {
                        throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
                    }
                    return new HandoffValidation(row.handoffId(), row.deliveryId(), row.attemptId(), row.expiresAt(), row.deliveryStatus(), row.deliveredQuantity());
                }).orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public DispatchHandoffValidation validateDispatchHandoff(DispatchHandoffValidationRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.deliveryId(), request.assignmentId(),
                request.driverMembershipId(), request.driverUserId(), request.tokenHash(), request.now());
        return jdbc.query("select h.id,h.delivery_id,h.assignment_id,h.delivery_version,h.expires_at,h.status "
                        + "from logistics.dispatch_handoff_identity h "
                        + "join logistics.delivery_assignment da on da.tenant_id=h.tenant_id "
                        + "and da.workspace_id=h.workspace_id and da.delivery_id=h.delivery_id "
                        + "and da.fulfillment_driver_assignment_id=h.assignment_id "
                        + "join logistics.fulfillment_driver_assignment a on a.tenant_id=da.tenant_id "
                        + "and a.workspace_id=da.workspace_id and a.id=da.fulfillment_driver_assignment_id "
                        + "join logistics.fulfillment f on f.tenant_id=a.tenant_id and f.workspace_id=a.workspace_id "
                        + "and f.id=a.fulfillment_id "
                        + "join logistics.delivery d on d.tenant_id=h.tenant_id and d.workspace_id=h.workspace_id "
                        + "and d.id=h.delivery_id "
                        + "where h.tenant_id=? and h.workspace_id=? and h.delivery_id=? and h.assignment_id=? "
                        + "and h.token_hash=? and h.status='ACTIVE' and h.expires_at>? "
                        + "and h.delivery_version=d.version and f.version=a.fulfillment_version+1 "
                        + "and a.responsible_membership_id=? and a.responsible_user_id=?",
                (rs, row) -> new DispatchHandoffValidation(rs.getObject("id", UUID.class),
                        rs.getObject("delivery_id", UUID.class), rs.getObject("assignment_id", UUID.class),
                        rs.getLong("delivery_version"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")),
                request.tenantId(), request.workspaceId(), request.deliveryId(), request.assignmentId(),
                request.tokenHash(), Timestamp.from(request.now()), request.driverMembershipId(), request.driverUserId())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
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
        if (DeliveryExecutionHoldGate.blocking(jdbc, request.tenantId(), request.workspaceId(), request.deliveryId()))
            throw error("DELIVERY_OPERATIONAL_EXCEPTION_BLOCKING", false);
        HandoffRow handoff = jdbc.query("select id,coalesce(delivery_attempt_id,delivery_active_attempt_id) delivery_attempt_id,customer_account_id,expires_at,status from logistics.delivery_handoff_token where tenant_id=? and workspace_id=? and delivery_id=? and token_hash=? for update",
                (rs, row) -> new HandoffRow(rs.getObject("id", UUID.class), request.deliveryId(), rs.getObject("delivery_attempt_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("status")), request.tenantId(), request.workspaceId(), request.deliveryId(), request.tokenHash())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
        UUID customer = jdbc.queryForObject("select customer_account_id from logistics.delivery_handoff_token where tenant_id=? and workspace_id=? and id=?", UUID.class,
                request.tenantId(), request.workspaceId(), handoff.id());
        if (!request.customerAccountId().equals(customer)) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        if (jdbc.queryForObject("select count(*) from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and delivery_attempt_id=?", Integer.class,
                request.tenantId(), request.workspaceId(), handoff.attemptId()) > 0) throw error("BUYER_RECEIPT_ALREADY_RECORDED", false);
        if (!isActiveDelivery(delivery.status())) throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
        if (!handoff.expiresAt().isAfter(databaseNow())) throw error("DELIVERY_HANDOFF_EXPIRED", false);
        if (!"ACTIVE".equals(handoff.status())) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        AttemptRow attempt = jdbc.query("select id,status from logistics.delivery_attempt where tenant_id=? and workspace_id=? and id=? and delivery_id=? for update",
                (rs, row) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getString("status")), request.tenantId(), request.workspaceId(), handoff.attemptId(), request.deliveryId())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_ATTEMPT_NOT_FOUND", true));
        if (isTerminalAttempt(attempt.status())) throw error("DELIVERY_HANDOFF_NOT_ACTIVE", false);
        // The attempt lock can also wait. Recheck with wall-clock database time
        // after every lock that can delay acceptance of the ephemeral handoff.
        if (!handoff.expiresAt().isAfter(databaseNow())) throw error("DELIVERY_HANDOFF_EXPIRED", false);
        BigDecimal delivered = jdbc.queryForObject("select coalesce(sum(coalesce(received_quantity, case when attempted_quantity is null then quantity else 0 end)),0) from logistics.delivery_attempt_line where tenant_id=? and workspace_id=? and delivery_attempt_id=?",
                BigDecimal.class, request.tenantId(), request.workspaceId(), handoff.attemptId());
        if (request.acceptedQuantity().compareTo(delivered) > 0
                || ("ACCEPTED".equals(request.decision()) && request.acceptedQuantity().compareTo(delivered) != 0)) {
            throw error("BUYER_RECEIPT_QUANTITY_INVALID", false);
        }
        // PostgreSQL CURRENT_TIMESTAMP is transaction-start time; clock_timestamp
        // makes the final TTL check observe time elapsed during the transaction.
        Instant occurredAt = databaseNow();
        if (!handoff.expiresAt().isAfter(occurredAt)) throw error("DELIVERY_HANDOFF_EXPIRED", false);
        UUID receiptId = UUID.randomUUID();
        jdbc.update("insert into logistics.buyer_receipt_fact(id,tenant_id,workspace_id,delivery_id,delivery_attempt_id,customer_account_id,buyer_membership_id,handoff_token_id,decision,driver_delivered_quantity,accepted_quantity,reason,occurred_at,idempotency_key,request_hash) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                receiptId, request.tenantId(), request.workspaceId(), request.deliveryId(), handoff.attemptId(), request.customerAccountId(),
                request.buyerMembershipId(), handoff.id(), request.decision(), delivered, request.acceptedQuantity(), request.reason(),
                Timestamp.from(occurredAt), request.idempotencyKey(), request.requestHash());
        if (jdbc.update("update logistics.delivery_handoff_token set status='CONSUMED' where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                request.tenantId(), request.workspaceId(), handoff.id()) != 1) throw error("CONCURRENCY_CONFLICT", false);
        return new BuyerReceipt(receiptId, request.deliveryId(), handoff.attemptId(), request.decision(), delivered,
                request.acceptedQuantity(), request.reason(), occurredAt, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BuyerReceiptAcceptance recordAcceptance(AcceptanceRequest request) {
        requireScope(request.tenantId(), request.workspaceId(), request.buyerMembershipId(),
                request.customerAccountId(), request.tokenHash(), request.idempotencyKey(),
                request.requestHash(), request.now());
        lockCommand(request.tenantId(), request.workspaceId(), request.buyerMembershipId(),
                "BUYER_RECEIPT", request.idempotencyKey());
        BuyerReceiptRow prior = jdbc.query("select id,delivery_id,delivery_attempt_id,decision,driver_delivered_quantity,accepted_quantity,reason,occurred_at from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and buyer_membership_id=? and idempotency_key=? for update",
                (rs, row) -> new BuyerReceiptRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getString("decision"),
                        rs.getBigDecimal("driver_delivered_quantity"), rs.getBigDecimal("accepted_quantity"),
                        rs.getString("reason"), rs.getTimestamp("occurred_at").toInstant()),
                request.tenantId(), request.workspaceId(), request.buyerMembershipId(), request.idempotencyKey())
                .stream().findFirst().orElse(null);
        if (prior != null) {
            ensureHash(jdbc.queryForObject("select request_hash from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and id=?",
                    String.class, request.tenantId(), request.workspaceId(), prior.id()), request.requestHash());
            UUID priorAccountId = jdbc.queryForObject("select customer_account_id from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and id=?",
                    UUID.class, request.tenantId(), request.workspaceId(), prior.id());
            if (!request.customerAccountId().equals(priorAccountId)) {
                throw error("BUYER_RELATIONSHIP_NOT_FOUND", false);
            }
            if (!"ACCEPTED".equals(prior.decision())) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            if (request.expectedDeliveryId() != null && !request.expectedDeliveryId().equals(prior.deliveryId())) {
                throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
            }
            if (request.expectedAcceptedQuantity() != null
                    && request.expectedAcceptedQuantity().compareTo(prior.accepted()) != 0) {
                throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
            }
            UUID orderId = salesOrderId(request.tenantId(), request.workspaceId(), prior.deliveryId());
            DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), prior.deliveryId());
            if (delivery == null) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
            SalesOrderFulfillmentQuery.Snapshot order = lockSalesOrder(request, orderId);
            PodIdentity pod = findPodForUpdate(request.tenantId(), request.workspaceId(), delivery)
                    .orElseThrow(() -> error("BUYER_ACCEPTANCE_POD_MISSING", false));
            if (pod.buyerMembershipId() != null && !pod.buyerMembershipId().equals(request.buyerMembershipId())) {
                throw error("BUYER_ACCEPTANCE_POD_MISMATCH", false);
            }
            if (!prior.id().equals(pod.buyerReceiptFactId())) {
                throw error("BUYER_ACCEPTANCE_POD_MISMATCH", false);
            }
            return new BuyerReceiptAcceptance(prior.id(), prior.deliveryId(), prior.attemptId(), pod.id(),
                    orderId, prior.accepted(), prior.occurredAt(), delivery.status(), pod.status(), delivery.version(),
                    false, allOrderQuantityReceived(request.tenantId(), request.workspaceId(), order), true);
        }

        HandoffRow binding = jdbc.query("select id,delivery_id,coalesce(delivery_attempt_id,delivery_active_attempt_id) delivery_attempt_id,expires_at,status from logistics.delivery_handoff_token where tenant_id=? and workspace_id=? and token_hash=? and customer_account_id=?",
                (rs, row) -> new HandoffRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")), request.tenantId(), request.workspaceId(), request.tokenHash(),
                request.customerAccountId()).stream().findFirst().orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
        if (request.expectedDeliveryId() != null && !request.expectedDeliveryId().equals(binding.deliveryId())) {
            throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        }

        DeliveryRow delivery = lockDelivery(request.tenantId(), request.workspaceId(), binding.deliveryId());
        if (delivery == null) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        if (DeliveryExecutionHoldGate.blocking(jdbc, request.tenantId(), request.workspaceId(), delivery.id())) {
            throw error("DELIVERY_OPERATIONAL_EXCEPTION_BLOCKING", false);
        }
        // Lock in delivery-then-order order, matching delivery outcome writes.
        // This serializes cumulative receipt checks across one order before insert.
        UUID orderId = salesOrderId(request.tenantId(), request.workspaceId(), binding.deliveryId());
        SalesOrderFulfillmentQuery.Snapshot order = lockSalesOrder(request, orderId);
        if ("CANCELLED".equals(order.status()) || "REJECTED".equals(order.status())) {
            throw error("SALES_ORDER_TRANSITION_INVALID", false);
        }
        HandoffRow handoff = jdbc.query("select id,delivery_id,coalesce(delivery_attempt_id,delivery_active_attempt_id) delivery_attempt_id,expires_at,status from logistics.delivery_handoff_token where tenant_id=? and workspace_id=? and id=? and token_hash=? and customer_account_id=? for update",
                (rs, row) -> new HandoffRow(rs.getObject("id", UUID.class), rs.getObject("delivery_id", UUID.class),
                        rs.getObject("delivery_attempt_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")), request.tenantId(), request.workspaceId(), binding.id(),
                request.tokenHash(), request.customerAccountId()).stream().findFirst()
                .orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
        if (!"ACTIVE".equals(handoff.status())) throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        if (!handoff.expiresAt().isAfter(databaseNow())) throw error("DELIVERY_HANDOFF_EXPIRED", false);
        if (!isTerminalDelivery(delivery.status())) throw error("DELIVERY_NOT_COMPLETED", false);
        if (!binding.deliveryId().equals(handoff.deliveryId())
                || !binding.attemptId().equals(handoff.attemptId())) {
            throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        }

        AttemptRow attempt = jdbc.query("select id,status,outcome from logistics.delivery_attempt where tenant_id=? and workspace_id=? and id=? and delivery_id=? for update",
                (rs, row) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("outcome")),
                request.tenantId(), request.workspaceId(), handoff.attemptId(), delivery.id())
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_ATTEMPT_NOT_FOUND", false));
        if (!isSuccessfulTerminalAttempt(delivery.status(), attempt.status(), attempt.outcome())) {
            throw error("DELIVERY_NOT_COMPLETED", false);
        }
        Integer priorReceiptCount = jdbc.queryForObject("select count(*) from logistics.buyer_receipt_fact where tenant_id=? and workspace_id=? and delivery_attempt_id=?",
                Integer.class, request.tenantId(), request.workspaceId(), attempt.id());
        if (priorReceiptCount == null || priorReceiptCount > 0) throw error("BUYER_RECEIPT_ALREADY_RECORDED", false);
        if (!handoff.expiresAt().isAfter(databaseNow())) throw error("DELIVERY_HANDOFF_EXPIRED", false);
        Integer unresolvedLines = jdbc.queryForObject("select count(*) from logistics.delivery_attempt_line where tenant_id=? and workspace_id=? and delivery_attempt_id=? and received_quantity is null",
                Integer.class, request.tenantId(), request.workspaceId(), attempt.id());
        if (unresolvedLines == null || unresolvedLines != 0) throw error("DELIVERY_QUANTITY_UNVERIFIED", false);
        Set<OrderLineIdentity> commercialLines = new HashSet<>();
        order.lines().forEach(line -> commercialLines.add(
                new OrderLineIdentity(line.skuId(), line.catalogItemId(), line.unit())));
        List<OrderLineIdentity> attemptedLines = jdbc.query(
                "select sku_id,catalog_item_id,unit from logistics.delivery_attempt_line "
                        + "where tenant_id=? and workspace_id=? and delivery_attempt_id=?",
                (rs, row) -> new OrderLineIdentity(rs.getObject("sku_id", UUID.class),
                        rs.getString("catalog_item_id"), rs.getString("unit")),
                request.tenantId(), request.workspaceId(), attempt.id());
        if (attemptedLines.stream().anyMatch(line -> !line.complete() || !commercialLines.contains(line))) {
            throw error("DELIVERY_QUANTITY_UNVERIFIED", false);
        }
        BigDecimal delivered = jdbc.queryForObject("select coalesce(sum(received_quantity),0) from logistics.delivery_attempt_line where tenant_id=? and workspace_id=? and delivery_attempt_id=?",
                BigDecimal.class, request.tenantId(), request.workspaceId(), attempt.id());
        if (delivered == null || delivered.signum() <= 0) throw error("DELIVERY_QUANTITY_UNVERIFIED", false);
        if (request.expectedAcceptedQuantity() != null
                && request.expectedAcceptedQuantity().compareTo(delivered) != 0) {
            throw error("BUYER_RECEIPT_QUANTITY_INVALID", false);
        }

        Instant occurredAt = databaseNow();
        if (!handoff.expiresAt().isAfter(occurredAt)) throw error("DELIVERY_HANDOFF_EXPIRED", false);
        UUID receiptId = UUID.randomUUID();
        jdbc.update("insert into logistics.buyer_receipt_fact(id,tenant_id,workspace_id,delivery_id,delivery_attempt_id,customer_account_id,buyer_membership_id,handoff_token_id,decision,driver_delivered_quantity,accepted_quantity,reason,occurred_at,idempotency_key,request_hash) values (?,?,?,?,?,?,?,?, 'ACCEPTED',?,?,null,?,?,?)",
                receiptId, request.tenantId(), request.workspaceId(), delivery.id(), attempt.id(), request.customerAccountId(),
                request.buyerMembershipId(), handoff.id(), delivered, delivered, Timestamp.from(occurredAt),
                request.idempotencyKey(), request.requestHash());
        if (jdbc.update("update logistics.delivery_handoff_token set status='CONSUMED' where tenant_id=? and workspace_id=? and id=? and status='ACTIVE'",
                request.tenantId(), request.workspaceId(), handoff.id()) != 1) throw error("CONCURRENCY_CONFLICT", false);

        PodIdentity pod = findPodForUpdate(request.tenantId(), request.workspaceId(), delivery).orElse(null);
        boolean podSealedNow = false;
        if (pod == null) {
            UUID podId = UUID.randomUUID();
            jdbc.update("insert into logistics.proof_of_delivery(id,tenant_id,workspace_id,dispatch_order_id,receiver_name,completed_at,notes,photo_evidence_declared,signature_evidence_declared,status,created_at,delivery_id,attempt_id,photo_evidence_object_id,signature_evidence_object_id,sealed_at,buyer_membership_id,buyer_receipt_fact_id) values (?,?,?,null,null,?,null,false,false,'PENDING',?,?,?,null,null,null,null,null)",
                    podId, request.tenantId(), request.workspaceId(), Timestamp.from(occurredAt),
                    Timestamp.from(occurredAt), delivery.id(), attempt.id());
            pod = new PodIdentity(podId, attempt.id(), null, occurredAt, "PENDING", null, null);
        }
        if (pod.attemptId() != null && !attempt.id().equals(pod.attemptId())) {
            throw error("POD_ATTEMPT_MISMATCH", false);
        }
        if (pod.buyerMembershipId() != null && !pod.buyerMembershipId().equals(request.buyerMembershipId())) {
            throw error("BUYER_ACCEPTANCE_POD_MISMATCH", false);
        }
        if (pod.buyerReceiptFactId() != null && !pod.buyerReceiptFactId().equals(receiptId)) {
            throw error("BUYER_ACCEPTANCE_POD_MISMATCH", false);
        }
        if ("REJECTED".equals(pod.status())) throw error("POD_REJECTED", false);
        if (!"SEALED".equals(pod.status()) && !"COMPLETED".equals(pod.status())) {
            if (!"PENDING".equals(pod.status()) && !"CAPTURED".equals(pod.status())) {
                throw error("POD_NOT_SEALABLE", false);
            }
            if (jdbc.update("update logistics.proof_of_delivery set status='SEALED',buyer_membership_id=?,buyer_receipt_fact_id=?,sealed_at=? where tenant_id=? and workspace_id=? and id=? and status in ('PENDING','CAPTURED') and (buyer_membership_id is null or buyer_membership_id=?) and (buyer_receipt_fact_id is null or buyer_receipt_fact_id=?)",
                    request.buyerMembershipId(), receiptId, Timestamp.from(occurredAt), request.tenantId(),
                    request.workspaceId(), pod.id(), request.buyerMembershipId(), receiptId) != 1) {
                throw error("CONCURRENCY_CONFLICT", false);
            }
            podSealedNow = true;
            pod = new PodIdentity(pod.id(), pod.attemptId(), pod.receiverName(), pod.completedAt(),
                    "SEALED", request.buyerMembershipId(), receiptId);
        } else if (pod.buyerMembershipId() == null || pod.buyerReceiptFactId() == null) {
            if (jdbc.update("update logistics.proof_of_delivery set buyer_membership_id=?,buyer_receipt_fact_id=? where tenant_id=? and workspace_id=? and id=? and status in ('SEALED','COMPLETED') and (buyer_membership_id is null or buyer_membership_id=?) and (buyer_receipt_fact_id is null or buyer_receipt_fact_id=?)",
                    request.buyerMembershipId(), receiptId, request.tenantId(), request.workspaceId(), pod.id(),
                    request.buyerMembershipId(), receiptId) != 1) {
                throw error("CONCURRENCY_CONFLICT", false);
            }
            pod = new PodIdentity(pod.id(), pod.attemptId(), pod.receiverName(), pod.completedAt(),
                    pod.status(), request.buyerMembershipId(), receiptId);
        }
        if (jdbc.update("update logistics.delivery set delivered_at=case when status='DELIVERED' then coalesce(delivered_at,?) else delivered_at end,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status=? and version=?",
                Timestamp.from(occurredAt), Timestamp.from(occurredAt), request.tenantId(), request.workspaceId(),
                delivery.id(), delivery.status(), delivery.version()) != 1) {
            throw error("CONCURRENCY_CONFLICT", false);
        }
        if (podSealedNow) {
            jdbc.update("insert into logistics.delivery_event(id,tenant_id,workspace_id,delivery_id,event_type,actor_membership_id,reason,occurred_at,attempt_id) values (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), request.tenantId(), request.workspaceId(), delivery.id(), "POD_SEALED",
                    request.buyerMembershipId(), "Buyer accepted the delivered quantities", Timestamp.from(occurredAt), attempt.id());
        }
        boolean allOrderQuantityReceived = allOrderQuantityReceived(request.tenantId(), request.workspaceId(), order);
        DeliveryCompletedOutbox.append(canonicalOutbox, request.tenantId(), request.workspaceId(),
                delivery.id(), pod.id(), receiptId, occurredAt);
        return new BuyerReceiptAcceptance(receiptId, delivery.id(), attempt.id(), pod.id(), orderId, delivered,
                occurredAt, delivery.status(), pod.status(), delivery.version() + 1,
                podSealedNow, allOrderQuantityReceived, false);
    }

    private java.util.Optional<PodIdentity> findPodForUpdate(UUID tenantId, UUID workspaceId, DeliveryRow delivery) {
        return jdbc.query("select id,attempt_id,receiver_name,completed_at,status,buyer_membership_id,buyer_receipt_fact_id "
                        + "from logistics.proof_of_delivery where tenant_id=? and workspace_id=? "
                        + "and (delivery_id=? or dispatch_order_id=?) "
                        + "order by case when delivery_id=? then 0 else 1 end limit 1 for update",
                (rs, row) -> new PodIdentity(rs.getObject("id", UUID.class), rs.getObject("attempt_id", UUID.class),
                        rs.getString("receiver_name"), rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("status"), rs.getObject("buyer_membership_id", UUID.class),
                        rs.getObject("buyer_receipt_fact_id", UUID.class)),
                tenantId, workspaceId, delivery.id(), delivery.dispatchOrderId(), delivery.id()).stream().findFirst();
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    private DeliveryRow lockDelivery(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query("select d.id,d.fulfillment_id,d.dispatch_order_id,d.status,d.version from logistics.delivery d where d.tenant_id=? and d.workspace_id=? and d.id=? for update",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("dispatch_order_id", UUID.class), rs.getString("status"), rs.getLong("version")),
                tenant, workspace, id).stream().findFirst().orElse(null);
    }

    private DeliveryRow findDelivery(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query("select d.id,d.fulfillment_id,d.dispatch_order_id,d.status,d.version from logistics.delivery d where d.tenant_id=? and d.workspace_id=? and d.id=?",
                (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getObject("fulfillment_id", UUID.class),
                        rs.getObject("dispatch_order_id", UUID.class), rs.getString("status"), rs.getLong("version")),
                tenant, workspace, id).stream().findFirst().orElse(null);
    }

    private UUID salesOrderId(UUID tenant, UUID workspace, UUID deliveryId) {
        return jdbc.query("select case when d.fulfillment_id is not null then f.sales_order_id else dispatch.sales_order_id end sales_order_id "
                        + "from logistics.delivery d "
                        + "left join logistics.fulfillment f on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "left join logistics.dispatch_order dispatch on dispatch.tenant_id=d.tenant_id and dispatch.workspace_id=d.workspace_id and dispatch.id=d.dispatch_order_id "
                        + "where d.tenant_id=? and d.workspace_id=? and d.id=? "
                        + "and case when d.fulfillment_id is not null then f.sales_order_id else dispatch.sales_order_id end is not null",
                (rs, row) -> rs.getObject("sales_order_id", UUID.class), tenant, workspace, deliveryId)
                .stream().findFirst().orElseThrow(() -> error("DELIVERY_HANDOFF_TOKEN_INVALID", false));
    }

    private SalesOrderFulfillmentQuery.Snapshot lockSalesOrder(AcceptanceRequest request, UUID salesOrderId) {
        SalesOrderFulfillmentQuery.Snapshot order;
        try {
            order = salesOrders.getForUpdate(request.tenantId(), request.workspaceId(), salesOrderId);
        } catch (RuntimeException exception) {
            throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        }
        if (!request.customerAccountId().equals(order.clientAccountId())) {
            throw error("DELIVERY_HANDOFF_TOKEN_INVALID", false);
        }
        return order;
    }

    /**
     * Receipt quantities are BC-06 facts; the commercial line targets come from
     * the locked BC-04 public snapshot, never from a cross-context SQL join.
     */
    private boolean allOrderQuantityReceived(UUID tenant, UUID workspace,
                                             SalesOrderFulfillmentQuery.Snapshot salesOrder) {
        List<AcceptedOrderLine> acceptedLines = jdbc.query("with accepted as ("
                        + "select dal.catalog_item_id,dal.sku_id,dal.unit,sum(dal.received_quantity) accepted_quantity "
                        + "from logistics.buyer_receipt_fact br "
                        + "join logistics.delivery_attempt da on da.tenant_id=br.tenant_id and da.workspace_id=br.workspace_id "
                        + "and da.id=br.delivery_attempt_id and da.delivery_id=br.delivery_id "
                        + "join logistics.delivery_attempt_line dal on dal.tenant_id=da.tenant_id and dal.workspace_id=da.workspace_id "
                        + "and dal.delivery_attempt_id=da.id "
                        + "join logistics.delivery d on d.tenant_id=br.tenant_id and d.workspace_id=br.workspace_id and d.id=br.delivery_id "
                        + "left join logistics.fulfillment f on f.tenant_id=d.tenant_id and f.workspace_id=d.workspace_id and f.id=d.fulfillment_id "
                        + "left join logistics.dispatch_order dispatch on dispatch.tenant_id=d.tenant_id and dispatch.workspace_id=d.workspace_id and dispatch.id=d.dispatch_order_id "
                        + "where br.tenant_id=? and br.workspace_id=? and br.decision='ACCEPTED' "
                        + "and case when d.fulfillment_id is not null then f.sales_order_id else dispatch.sales_order_id end=? "
                        + "and dal.received_quantity is not null "
                        + "and not exists (select 1 from logistics.delivery_attempt_line missing "
                        + "where missing.tenant_id=br.tenant_id and missing.workspace_id=br.workspace_id "
                        + "and missing.delivery_attempt_id=br.delivery_attempt_id and missing.received_quantity is null) "
                        + "and br.accepted_quantity=(select coalesce(sum(actual.received_quantity),0) "
                        + "from logistics.delivery_attempt_line actual where actual.tenant_id=br.tenant_id "
                        + "and actual.workspace_id=br.workspace_id and actual.delivery_attempt_id=br.delivery_attempt_id) "
                        + "group by dal.catalog_item_id,dal.sku_id,dal.unit) "
                        + "select catalog_item_id,sku_id,unit,accepted_quantity from accepted",
                (rs, row) -> new AcceptedOrderLine(
                        new OrderLineIdentity(rs.getObject("sku_id", UUID.class),
                                rs.getString("catalog_item_id"), rs.getString("unit")),
                        rs.getBigDecimal("accepted_quantity")), tenant, workspace, salesOrder.id());
        Map<OrderLineIdentity, BigDecimal> acceptedByLine = new HashMap<>();
        acceptedLines.stream().filter(line -> line.identity().complete())
                .forEach(line -> acceptedByLine.put(line.identity(), line.quantity()));
        return !salesOrder.lines().isEmpty() && salesOrder.lines().stream().allMatch(line -> {
            OrderLineIdentity identity = new OrderLineIdentity(line.skuId(), line.catalogItemId(), line.unit());
            return identity.complete()
                    && acceptedByLine.getOrDefault(identity, BigDecimal.ZERO).compareTo(line.quantity()) == 0;
        });
    }

    private DispatchBinding lockCurrentDispatchBinding(UUID tenant, UUID workspace, UUID deliveryId,
                                                        UUID assignmentId) {
        return jdbc.query("select d.version delivery_version from logistics.fulfillment_driver_assignment a "
                        + "join logistics.fulfillment f on f.tenant_id=a.tenant_id and f.workspace_id=a.workspace_id "
                        + "and f.id=a.fulfillment_id and f.version=a.fulfillment_version+1 "
                        + "join logistics.delivery_assignment da on da.tenant_id=a.tenant_id "
                        + "and da.workspace_id=a.workspace_id and da.fulfillment_driver_assignment_id=a.id "
                        + "and da.delivery_id=? "
                        + "join logistics.delivery d on d.tenant_id=da.tenant_id and d.workspace_id=da.workspace_id "
                        + "and d.id=da.delivery_id where a.tenant_id=? and a.workspace_id=? and a.id=? "
                        + "for update of f,d",
                (rs, row) -> new DispatchBinding(rs.getLong("delivery_version")),
                deliveryId, tenant, workspace, assignmentId).stream().findFirst().orElse(null);
    }

    private UUID customerAccount(UUID tenant, UUID workspace, DeliveryRow delivery) {
        UUID salesOrderId = salesOrderId(tenant, workspace, delivery.id());
        SalesOrderFulfillmentQuery.Snapshot order;
        try {
            order = salesOrders.get(tenant, workspace, salesOrderId);
        } catch (RuntimeException exception) {
            throw error("BUYER_RELATIONSHIP_NOT_FOUND", false);
        }
        if (order.clientAccountId() == null) throw error("BUYER_RELATIONSHIP_NOT_FOUND", false);
        return order.clientAccountId();
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
    private static boolean isTerminalDelivery(String status) {
        return "DELIVERED".equals(status) || "PARTIAL".equals(status);
    }
    private static boolean isSuccessfulTerminalAttempt(String deliveryStatus, String attemptStatus, String outcome) {
        return ("DELIVERED".equals(deliveryStatus) && "FINAL".equals(attemptStatus)
                && "DELIVERED".equals(outcome))
                || (("PARTIAL".equals(deliveryStatus) || "DELIVERED".equals(deliveryStatus))
                && "PARTIAL".equals(attemptStatus)
                && "PARTIAL".equals(outcome));
    }
    private static boolean isTerminalAttempt(String status) {
        return "FINAL".equals(status) || "PARTIAL".equals(status)
                || "FAILED".equals(status) || "REJECTED".equals(status);
    }
    private static FulfillmentOperationException error(String code, boolean notFound) { return new FulfillmentOperationException(code, notFound); }

    private record DeliveryRow(UUID id, UUID fulfillmentId, UUID dispatchOrderId, String status, long version) { }
    private record AttemptRow(UUID id, String status, String outcome) {
        private AttemptRow(UUID id, String status) { this(id, status, null); }
    }
    private record HandoffRow(UUID id, UUID deliveryId, UUID attemptId, Instant expiresAt, String status) { }
    private record DispatchBinding(long deliveryVersion) { }
    private record DispatchHandoffRow(UUID id, UUID deliveryId, UUID assignmentId, long deliveryVersion,
                                      Instant expiresAt, String status, String requestHash) { }
    private record ValidationRow(UUID handoffId, UUID deliveryId, UUID attemptId, Instant expiresAt,
                                 String handoffStatus, String deliveryStatus, String attemptStatus,
                                 String attemptOutcome, BigDecimal deliveredQuantity) { }
    private record PodIdentity(UUID id, UUID attemptId, String receiverName, Instant completedAt,
                               String status, UUID buyerMembershipId, UUID buyerReceiptFactId) { }
    private record OrderLineIdentity(UUID skuId, String catalogItemId, String unit) {
        private boolean complete() { return skuId != null && catalogItemId != null && unit != null; }
    }
    private record AcceptedOrderLine(OrderLineIdentity identity, BigDecimal quantity) { }
    private record BuyerReceiptRow(UUID id, UUID deliveryId, UUID attemptId, String decision, BigDecimal delivered,
                                   BigDecimal accepted, String reason, Instant occurredAt) {
        private BuyerReceipt toResult(boolean replayed) { return new BuyerReceipt(id, deliveryId, attemptId, decision, delivered, accepted, reason, occurredAt, replayed); }
    }
}
