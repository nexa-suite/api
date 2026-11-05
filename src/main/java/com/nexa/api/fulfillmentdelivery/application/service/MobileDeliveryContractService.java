package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.port.MobileDeliveryContractPort;
import com.nexa.api.fulfillmentdelivery.application.publicapi.MobileDeliveryContractOperations;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for G03/G04; tokens are opaque and receipt facts immutable. */
@Service
@Profile("!test")
public class MobileDeliveryContractService implements MobileDeliveryContractOperations {
    private final MobileDeliveryContractPort persistence;
    private final CustomerAccountQuery accounts;
    private final BusinessTraceabilityCommands traceability;
    private final SalesOrderFulfillmentCommands salesOrderCommands;
    private final SalesOrderFulfillmentQuery salesOrders;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration handoffTtl;

    @Autowired
    public MobileDeliveryContractService(MobileDeliveryContractPort persistence,
                                         CustomerAccountQuery accounts,
                                         BusinessTraceabilityCommands traceability,
                                         SalesOrderFulfillmentCommands salesOrderCommands,
                                         SalesOrderFulfillmentQuery salesOrders,
                                         Clock clock,
                                         @Value("${nexa.mobile.delivery-handoff-ttl:PT10M}") Duration handoffTtl) {
        this.persistence = Objects.requireNonNull(persistence, "Mobile delivery persistence is required");
        this.accounts = Objects.requireNonNull(accounts, "Customer accounts are required");
        this.traceability = Objects.requireNonNull(traceability, "Traceability is required");
        this.salesOrderCommands = salesOrderCommands;
        this.salesOrders = salesOrders;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.random = new SecureRandom();
        if (handoffTtl == null || handoffTtl.isNegative() || handoffTtl.isZero() || handoffTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Delivery handoff TTL must be between one second and 24 hours");
        }
        this.handoffTtl = handoffTtl;
    }

    public MobileDeliveryContractService(MobileDeliveryContractPort persistence,
                                         CustomerAccountQuery accounts,
                                         BusinessTraceabilityCommands traceability,
                                         Clock clock, Duration handoffTtl) {
        this(persistence, accounts, traceability, null, null, clock, handoffTtl);
    }

    @Transactional
    @Override
    public MobileDeliveryContractOperations.IssuedHandoff issue(CurrentAccessContext context, UUID deliveryId,
                                                                 UUID attemptId, String idempotencyKey) {
        context.requirePermission(Permission.LOGISTICS_WRITE);
        requireKey(idempotencyKey);
        if (deliveryId == null || attemptId == null) throw invalid("DELIVERY_HANDOFF_REQUEST_INVALID");
        Instant issuedAt = clock.instant();
        String rawToken = token();
        MobileDeliveryContractPort.HandoffIssue result = persistence.issue(new MobileDeliveryContractPort.IssueRequest(
                context.tenantId().value(), context.workspaceId().value(), deliveryId, attemptId,
                context.membershipId().value(), context.userId().value(), idempotencyKey.trim(),
                hash("handoff-issue-v1|" + deliveryId + "|" + attemptId), hash(rawToken), issuedAt,
                issuedAt.plus(handoffTtl)));
        if (!result.replayed()) {
            trace(context, "DELIVERY_HANDOFF_TOKEN_ISSUED", "DeliveryHandoffToken", result.handoffId(),
                    idempotencyKey, java.util.Map.of("deliveryId", deliveryId, "attemptId", attemptId,
                            "expiresAt", result.expiresAt().toString()));
        }
        return new MobileDeliveryContractOperations.IssuedHandoff(result.handoffId(), result.deliveryId(),
                result.attemptId(), result.expiresAt(), result.status(), result.replayed() ? null : rawToken);
    }

    @Transactional
    @Override
    public MobileDeliveryContractOperations.IssuedDispatchHandoff issueDispatchHandoff(
            CurrentAccessContext context, UUID deliveryId, UUID assignmentId, String idempotencyKey) {
        context.requirePermission(Permission.LOGISTICS_WRITE);
        requireKey(idempotencyKey);
        if (deliveryId == null || assignmentId == null) throw invalid("DELIVERY_HANDOFF_REQUEST_INVALID");
        Instant issuedAt = clock.instant();
        String rawToken = token();
        MobileDeliveryContractPort.DispatchHandoffIssue result = persistence.issueDispatchHandoff(
                new MobileDeliveryContractPort.DispatchHandoffIssueRequest(
                        context.tenantId().value(), context.workspaceId().value(), deliveryId, assignmentId,
                        context.membershipId().value(), idempotencyKey.trim(),
                        hash("dispatch-handoff-v1|" + deliveryId + "|" + assignmentId), hash(rawToken),
                        issuedAt, issuedAt.plus(handoffTtl)));
        if (!result.replayed()) {
            trace(context, "DISPATCH_HANDOFF_IDENTITY_ISSUED", "DispatchHandoffIdentity", result.handoffId(),
                    idempotencyKey, java.util.Map.of("deliveryId", deliveryId, "assignmentId", assignmentId,
                            "deliveryVersion", result.deliveryVersion(), "expiresAt", result.expiresAt().toString()));
        }
        return new MobileDeliveryContractOperations.IssuedDispatchHandoff("DISPATCH_HANDOFF", result.handoffId(), result.deliveryId(),
                result.assignmentId(), result.deliveryVersion(), result.expiresAt(), result.status(),
                result.replayed() ? null : rawToken);
    }

    @Transactional(readOnly = true)
    @Override
    public MobileDeliveryContractOperations.HandoffValidation validate(CurrentAccessContext context,
                                                                         String rawToken) {
        requireBuyer(context, PermissionKey.BUYER_TRACKING_READ);
        String token = requireToken(rawToken);
        UUID customerAccountId = buyerAccount(context);
        MobileDeliveryContractPort.HandoffValidation result = persistence.validate(
                new MobileDeliveryContractPort.ValidationRequest(
                context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(),
                customerAccountId, hash(token), clock.instant()));
        return new MobileDeliveryContractOperations.HandoffValidation(result.handoffId(), result.deliveryId(),
                result.attemptId(), result.expiresAt(), result.deliveryStatus(), result.deliveredQuantity());
    }

    @Transactional(readOnly = true)
    @Override
    public MobileDeliveryContractOperations.DispatchHandoffValidation validateDispatchHandoff(
            CurrentAccessContext context, UUID deliveryId, UUID assignmentId, String rawToken) {
        context.requirePermission(Permission.LOGISTICS_READ);
        if (deliveryId == null || assignmentId == null) throw invalid("DELIVERY_HANDOFF_TOKEN_INVALID");
        String token = requireToken(rawToken);
        MobileDeliveryContractPort.DispatchHandoffValidation result = persistence.validateDispatchHandoff(
                new MobileDeliveryContractPort.DispatchHandoffValidationRequest(
                context.tenantId().value(), context.workspaceId().value(), deliveryId, assignmentId,
                context.membershipId().value(), context.userId().value(), hash(token), clock.instant()));
        return new MobileDeliveryContractOperations.DispatchHandoffValidation(result.handoffId(), result.deliveryId(),
                result.assignmentId(), result.deliveryVersion(), result.expiresAt(), result.status());
    }

    @Transactional
    @Override
    public MobileDeliveryContractOperations.BuyerReceipt recordReceipt(CurrentAccessContext context, UUID deliveryId,
                                                                        String rawToken, String decision,
                                                                        BigDecimal acceptedQuantity, String reason,
                                                                        String idempotencyKey) {
        requireBuyer(context, PermissionKey.BUYER_ORDER_READ);
        requireKey(idempotencyKey);
        String normalizedDecision = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!normalizedDecision.equals("ACCEPTED") && !normalizedDecision.equals("DISPUTED")) {
            throw invalid("BUYER_RECEIPT_DECISION_INVALID");
        }
        if (normalizedDecision.equals("ACCEPTED")) {
            MobileDeliveryContractOperations.BuyerReceiptAcceptance accepted = recordAcceptance(
                    context, rawToken, deliveryId, acceptedQuantity, idempotencyKey);
            return new MobileDeliveryContractOperations.BuyerReceipt(accepted.receiptId(), accepted.deliveryId(),
                    accepted.attemptId(), "ACCEPTED", accepted.acceptedQuantity(), accepted.acceptedQuantity(),
                    null, accepted.occurredAt(), accepted.deliveryStatus(), accepted.podStatus(),
                    accepted.salesOrderStatus(), accepted.deliveryVersion(), accepted.orderCompleted(),
                    accepted.replayed());
        }
        if (deliveryId == null) throw invalid("DELIVERY_HANDOFF_REQUEST_INVALID");
        if (acceptedQuantity == null || acceptedQuantity.signum() < 0) throw invalid("BUYER_RECEIPT_QUANTITY_INVALID");
        String boundedReason = bounded(reason);
        if (boundedReason == null || boundedReason.isBlank()) {
            throw invalid("BUYER_RECEIPT_REASON_REQUIRED");
        }
        String token = requireToken(rawToken);
        UUID customerAccountId = buyerAccount(context);
        MobileDeliveryContractPort.BuyerReceipt receipt = persistence.recordReceipt(
                new MobileDeliveryContractPort.ReceiptRequest(context.tenantId().value(), context.workspaceId().value(),
                        deliveryId, context.membershipId().value(), customerAccountId, hash(token), normalizedDecision,
                        acceptedQuantity, boundedReason, idempotencyKey.trim(),
                        hash("buyer-receipt-v1|" + deliveryId + "|" + hash(token) + "|" + normalizedDecision
                                + "|" + acceptedQuantity + "|" + Objects.toString(boundedReason, "<null>")), clock.instant()));
        if (!receipt.replayed()) {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("buyerMembershipId", context.membershipId().value());
            metadata.put("customerAccountId", customerAccountId);
            metadata.put("deliveryId", deliveryId);
            metadata.put("attemptId", receipt.attemptId());
            metadata.put("decision", receipt.decision());
            metadata.put("driverDeliveredQuantity", receipt.driverDeliveredQuantity());
            metadata.put("acceptedQuantity", receipt.acceptedQuantity());
            if (receipt.reason() != null) metadata.put("reason", receipt.reason());
            trace(context, "BUYER_RECEIPT_RECORDED", "BuyerReceiptFact", receipt.id(), idempotencyKey, metadata);
        }
        return new MobileDeliveryContractOperations.BuyerReceipt(receipt.id(), receipt.deliveryId(),
                receipt.attemptId(), receipt.decision(), receipt.driverDeliveredQuantity(),
                receipt.acceptedQuantity(), receipt.reason(), receipt.occurredAt(), receipt.replayed());
    }

    @Transactional
    @Override
    public MobileDeliveryContractOperations.BuyerReceiptAcceptance acceptReceipt(
            CurrentAccessContext context, String rawToken, UUID expectedDeliveryId, String idempotencyKey) {
        requireBuyer(context, PermissionKey.BUYER_ORDER_READ);
        requireKey(idempotencyKey);
        return recordAcceptance(context, rawToken, expectedDeliveryId, null, idempotencyKey);
    }

    private MobileDeliveryContractOperations.BuyerReceiptAcceptance recordAcceptance(
            CurrentAccessContext context, String rawToken, UUID expectedDeliveryId,
            BigDecimal expectedAcceptedQuantity, String idempotencyKey) {
        String token = requireToken(rawToken);
        UUID customerAccountId = buyerAccount(context);
        String tokenHash = hash(token);
        MobileDeliveryContractPort.BuyerReceiptAcceptance accepted = persistence.recordAcceptance(
                new MobileDeliveryContractPort.AcceptanceRequest(context.tenantId().value(),
                        context.workspaceId().value(), context.membershipId().value(), customerAccountId,
                        tokenHash, expectedDeliveryId, expectedAcceptedQuantity, idempotencyKey.trim(),
                        hash("buyer-receipt-accepted-v1|" + tokenHash), clock.instant()));
        if (salesOrders == null) {
            throw new IllegalStateException("Sales Order query is required for Buyer receipt acceptance");
        }
        SalesOrderFulfillmentQuery.Snapshot salesOrder = salesOrders.getForUpdate(context.tenantId().value(),
                context.workspaceId().value(), accepted.salesOrderId());
        if (salesOrder.clientAccountId() == null || !customerAccountId.equals(salesOrder.clientAccountId())) {
            throw invalid("BUYER_RELATIONSHIP_NOT_FOUND");
        }
        if (!accepted.replayed()) {
            if ("CANCELLED".equals(salesOrder.status()) || "REJECTED".equals(salesOrder.status())) {
                throw invalid("SALES_ORDER_TRANSITION_INVALID");
            }
            if (accepted.allOrderQuantityReceived()) {
                if (salesOrderCommands == null) {
                    throw new IllegalStateException("Sales Order commands are required for Buyer receipt acceptance");
                }
                if (!"COMPLETED".equals(salesOrder.status())) {
                    salesOrderCommands.markCompleted(context.tenantId().value(), context.workspaceId().value(),
                            accepted.salesOrderId(), context.membershipId().value(), accepted.occurredAt(),
                            "Buyer accepted the full ordered quantity", BigDecimal.ZERO);
                }
            } else if (accepted.acceptedQuantity().signum() > 0 && salesOrderCommands != null
                    && !"COMPLETED".equals(salesOrder.status())) {
                salesOrderCommands.markPartiallyDelivered(context.tenantId().value(), context.workspaceId().value(),
                        accepted.salesOrderId(), context.membershipId().value(), accepted.occurredAt(),
                        "Buyer accepted a partial delivered quantity");
            }
        }
        String salesOrderStatus = salesOrders.get(context.tenantId().value(), context.workspaceId().value(),
                accepted.salesOrderId()).status();
        boolean orderCompleted = accepted.allOrderQuantityReceived()
                && "COMPLETED".equals(salesOrderStatus);
        if (!accepted.replayed()) {
            java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("buyerMembershipId", context.membershipId().value());
            metadata.put("customerAccountId", customerAccountId);
            metadata.put("deliveryId", accepted.deliveryId());
            metadata.put("attemptId", accepted.attemptId());
            metadata.put("decision", "ACCEPTED");
            metadata.put("acceptedQuantity", accepted.acceptedQuantity());
            metadata.put("podId", accepted.podId());
            trace(context, "BUYER_RECEIPT_RECORDED", "BuyerReceiptFact", accepted.receiptId(),
                    idempotencyKey, metadata);
            if (accepted.podSealedNow()) {
                trace(context, "POD_SEALED", "ProofOfDelivery", accepted.podId(),
                        accepted.podId().toString(), metadata);
            }
        }
        return new MobileDeliveryContractOperations.BuyerReceiptAcceptance(accepted.receiptId(),
                accepted.deliveryId(), accepted.attemptId(), accepted.podId(), accepted.acceptedQuantity(),
                accepted.occurredAt(), accepted.deliveryStatus(), accepted.podStatus(), salesOrderStatus,
                accepted.deliveryVersion(), orderCompleted, accepted.replayed());
    }

    private UUID buyerAccount(CurrentAccessContext context) {
        return accounts.findBuyerReference(context.tenantId().toString(), context.workspaceId().toString(),
                        context.membershipId().toString())
                .map(value -> UUID.fromString(value.id()))
                .orElseThrow(() -> invalid("BUYER_RELATIONSHIP_NOT_FOUND"));
    }

    private static void requireBuyer(CurrentAccessContext context, PermissionKey permission) {
        if (!context.hasRole(MembershipRole.BUYER)) throw invalid("BUYER_ONLY_OPERATION");
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(permission);
    }

    private void trace(CurrentAccessContext context, String eventType, String subjectType, UUID subjectId,
                       String occurrenceKey, java.util.Map<String, Object> metadata) {
        traceability.record(new BusinessTraceabilityCommands.TraceRequest(context.tenantId().value(),
                context.workspaceId().value(), context.membershipId().value(), "FULFILLMENT_DELIVERY", eventType,
                subjectType, subjectId, occurrenceKey, occurrenceKey, metadata, clock.instant()));
    }

    private String token() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String requireToken(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 400) throw invalid("DELIVERY_HANDOFF_TOKEN_INVALID");
        return value.trim();
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim().replace('\r', ' ').replace('\n', ' ');
        return result.length() <= 2000 ? result : result.substring(0, 2000);
    }

    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) throw invalid("IDEMPOTENCY_KEY_REQUIRED");
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static FulfillmentOperationException invalid(String code) {
        return new FulfillmentOperationException(code, false);
    }

}
