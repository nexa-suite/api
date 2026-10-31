package com.nexa.api.fulfillmentdelivery.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.modulith.NamedInterface;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Public Mobile delivery handoff and buyer receipt operations. */
@NamedInterface(value = "fulfillment-tenant-composition", propagate = false)
public interface MobileDeliveryContractOperations {
    IssuedHandoff issue(CurrentAccessContext context, UUID deliveryId, UUID attemptId, String idempotencyKey);

    IssuedDispatchHandoff issueDispatchHandoff(CurrentAccessContext context, UUID deliveryId,
                                               UUID assignmentId, String idempotencyKey);

    HandoffValidation validate(CurrentAccessContext context, String rawToken);

    DispatchHandoffValidation validateDispatchHandoff(CurrentAccessContext context, UUID deliveryId,
                                                       UUID assignmentId, String rawToken);

    BuyerReceipt recordReceipt(CurrentAccessContext context, UUID deliveryId, String rawToken, String decision,
                               BigDecimal acceptedQuantity, String reason, String idempotencyKey);

    default BuyerReceiptAcceptance acceptReceipt(CurrentAccessContext context, String rawToken,
                                                  String idempotencyKey) {
        return acceptReceipt(context, rawToken, null, idempotencyKey);
    }

    BuyerReceiptAcceptance acceptReceipt(CurrentAccessContext context, String rawToken,
                                         UUID expectedDeliveryId, String idempotencyKey);

    record IssuedHandoff(UUID handoffId, UUID deliveryId, UUID attemptId, Instant expiresAt,
                         String status, String token) { }

    record IssuedDispatchHandoff(String purpose, UUID handoffId, UUID deliveryId, UUID assignmentId,
                                 long deliveryVersion, Instant expiresAt, String status, String token) { }

    record HandoffValidation(UUID handoffId, UUID deliveryId, UUID attemptId, Instant expiresAt,
                             String deliveryStatus, BigDecimal deliveredQuantity) { }

    record DispatchHandoffValidation(UUID handoffId, UUID deliveryId, UUID assignmentId,
                                     long deliveryVersion, Instant expiresAt, String status) { }

    record BuyerReceipt(UUID id, UUID deliveryId, UUID attemptId,
                        @Schema(allowableValues = {"ACCEPTED", "DISPUTED"}) String decision,
                        BigDecimal driverDeliveredQuantity, BigDecimal acceptedQuantity,
                        @Schema(nullable = true) String reason, Instant occurredAt,
                        @Schema(nullable = true) String deliveryStatus,
                        @Schema(nullable = true) String podStatus,
                        @Schema(nullable = true) String salesOrderStatus,
                        long deliveryVersion, boolean orderCompleted,
                        boolean replayed) {
        public BuyerReceipt(UUID id, UUID deliveryId, UUID attemptId, String decision,
                            BigDecimal driverDeliveredQuantity, BigDecimal acceptedQuantity,
                            String reason, Instant occurredAt, boolean replayed) {
            this(id, deliveryId, attemptId, decision, driverDeliveredQuantity, acceptedQuantity,
                    reason, occurredAt, null, null, null, 0L, false, replayed);
        }
    }

    record BuyerReceiptAcceptance(UUID receiptId, UUID deliveryId, UUID attemptId, UUID podId,
                                  BigDecimal acceptedQuantity, Instant occurredAt,
                                  String deliveryStatus, String podStatus, String salesOrderStatus,
                                  long deliveryVersion, boolean orderCompleted, boolean replayed) { }
}
