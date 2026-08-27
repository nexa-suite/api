package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BC-07 financial correction boundary; provider execution stays in BC-08. */
public interface FinancialAdjustmentCommands {
    Result postFinalQuantityAdjustment(Request request);

    Result post(Request request);

    record Request(UUID tenantId, UUID workspaceId, UUID actorMembershipId, UUID createdByIdentityId,
                   UUID receivableId, UUID salesOrderId, UUID deliveryId, UUID sourceId,
                   String adjustmentKind, String effect, BigDecimal amount, String currency,
                   String reason, String sourceType, String idempotencyKey, String requestHash,
                   String obligationType, Long expectedReceivableVersion, Instant now) {
        public Request {
            if (tenantId == null || workspaceId == null || actorMembershipId == null || createdByIdentityId == null
                    || amount == null || amount.signum() <= 0 || currency == null || currency.isBlank()
                    || reason == null || reason.isBlank() || sourceType == null || sourceType.isBlank()
                    || sourceId == null || idempotencyKey == null || idempotencyKey.isBlank()
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}") || now == null) {
                throw new IllegalArgumentException("Financial adjustment request is incomplete");
            }
            currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
            reason = reason.trim();
            sourceType = sourceType.trim();
            idempotencyKey = idempotencyKey.trim();
            effect = effect == null ? "DECREASE" : effect.trim().toUpperCase(java.util.Locale.ROOT);
            adjustmentKind = adjustmentKind == null ? "CORRECTION" : adjustmentKind.trim().toUpperCase(java.util.Locale.ROOT);
            obligationType = obligationType == null ? "CUSTOMER_CREDIT" : obligationType.trim().toUpperCase(java.util.Locale.ROOT);
            if (expectedReceivableVersion != null && expectedReceivableVersion < 0) {
                throw new IllegalArgumentException("Expected receivable version cannot be negative");
            }
        }
    }

    record Result(UUID adjustmentId, UUID receivableId, BigDecimal deltaAmount,
                  BigDecimal adjustedAmount, BigDecimal outstandingAmount,
                  UUID obligationId, String obligationType, long receivableVersion) { }
}
