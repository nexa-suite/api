package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.port.MobileDeliveryContractPort;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
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
public class MobileDeliveryContractService {
    private final MobileDeliveryContractPort persistence;
    private final CustomerAccountQuery accounts;
    private final BusinessTraceabilityCommands traceability;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration handoffTtl;

    public MobileDeliveryContractService(MobileDeliveryContractPort persistence,
                                         CustomerAccountQuery accounts,
                                         BusinessTraceabilityCommands traceability,
                                         Clock clock,
                                         @Value("${nexa.mobile.delivery-handoff-ttl:PT10M}") Duration handoffTtl) {
        this.persistence = Objects.requireNonNull(persistence, "Mobile delivery persistence is required");
        this.accounts = Objects.requireNonNull(accounts, "Customer accounts are required");
        this.traceability = Objects.requireNonNull(traceability, "Traceability is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.random = new SecureRandom();
        if (handoffTtl == null || handoffTtl.isNegative() || handoffTtl.isZero() || handoffTtl.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Delivery handoff TTL must be between one second and 24 hours");
        }
        this.handoffTtl = handoffTtl;
    }

    @Transactional
    public IssuedHandoff issue(CurrentAccessContext context, UUID deliveryId, UUID attemptId, String idempotencyKey) {
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
        return new IssuedHandoff(result.handoffId(), result.deliveryId(), result.attemptId(),
                result.expiresAt(), result.status(), result.replayed() ? null : rawToken);
    }

    @Transactional(readOnly = true)
    public MobileDeliveryContractPort.HandoffValidation validate(CurrentAccessContext context, String rawToken) {
        requireBuyer(context, PermissionKey.BUYER_TRACKING_READ);
        String token = requireToken(rawToken);
        UUID customerAccountId = buyerAccount(context);
        return persistence.validate(new MobileDeliveryContractPort.ValidationRequest(
                context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(),
                customerAccountId, hash(token), clock.instant()));
    }

    @Transactional
    public MobileDeliveryContractPort.BuyerReceipt recordReceipt(CurrentAccessContext context, UUID deliveryId,
                                                                  String rawToken, String decision,
                                                                  BigDecimal acceptedQuantity, String reason,
                                                                  String idempotencyKey) {
        requireBuyer(context, PermissionKey.BUYER_ORDER_READ);
        requireKey(idempotencyKey);
        if (deliveryId == null) throw invalid("DELIVERY_HANDOFF_REQUEST_INVALID");
        String normalizedDecision = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
        if (!normalizedDecision.equals("ACCEPTED") && !normalizedDecision.equals("DISPUTED")) {
            throw invalid("BUYER_RECEIPT_DECISION_INVALID");
        }
        if (acceptedQuantity == null || acceptedQuantity.signum() < 0) throw invalid("BUYER_RECEIPT_QUANTITY_INVALID");
        String boundedReason = bounded(reason);
        if (normalizedDecision.equals("DISPUTED") && (boundedReason == null || boundedReason.isBlank())) {
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
        return receipt;
    }

    private UUID buyerAccount(CurrentAccessContext context) {
        return accounts.findBuyerReference(context.tenantId().toString(), context.workspaceId().toString(),
                        context.membershipId().toString())
                .map(value -> UUID.fromString(value.id()))
                .orElseThrow(() -> invalid("BUYER_RELATIONSHIP_NOT_FOUND"));
    }

    private static void requireBuyer(CurrentAccessContext context, PermissionKey permission) {
        if (!context.hasRole(MembershipRole.BUYER)) throw invalid("BUYER_ONLY_OPERATION");
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

    public record IssuedHandoff(UUID handoffId, UUID deliveryId, UUID attemptId, Instant expiresAt,
                                String status, String token) { }
}
