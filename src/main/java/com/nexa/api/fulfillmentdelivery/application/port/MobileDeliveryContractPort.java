package com.nexa.api.fulfillmentdelivery.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BC-06 persistence boundary for the ephemeral buyer handoff and receipt fact. */
public interface MobileDeliveryContractPort {
    HandoffIssue issue(IssueRequest request);

    HandoffValidation validate(ValidationRequest request);

    BuyerReceipt recordReceipt(ReceiptRequest request);

    record IssueRequest(UUID tenantId, UUID workspaceId, UUID deliveryId, UUID attemptId,
                        UUID actorMembershipId, UUID actorUserId, String idempotencyKey,
                        String requestHash, String tokenHash, Instant issuedAt, Instant expiresAt) { }

    record HandoffIssue(UUID handoffId, UUID deliveryId, UUID attemptId, Instant expiresAt,
                        String status, boolean replayed) { }

    record ValidationRequest(UUID tenantId, UUID workspaceId, UUID buyerMembershipId,
                             UUID customerAccountId, String tokenHash, Instant now) { }

    record HandoffValidation(UUID handoffId, UUID deliveryId, UUID attemptId,
                             Instant expiresAt, String deliveryStatus, BigDecimal deliveredQuantity) { }

    record ReceiptRequest(UUID tenantId, UUID workspaceId, UUID deliveryId,
                          UUID buyerMembershipId, UUID customerAccountId, String tokenHash,
                          String decision, BigDecimal acceptedQuantity, String reason,
                          String idempotencyKey, String requestHash, Instant now) { }

    record BuyerReceipt(UUID id, UUID deliveryId, UUID attemptId, String decision,
                        BigDecimal driverDeliveredQuantity, BigDecimal acceptedQuantity,
                        String reason, Instant occurredAt, boolean replayed) { }
}
