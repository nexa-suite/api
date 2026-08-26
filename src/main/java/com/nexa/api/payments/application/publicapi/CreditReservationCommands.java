package com.nexa.api.payments.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Payments-owned credit reservation commands participating in the caller's local transaction. */
public interface CreditReservationCommands {
    void reserve(UUID tenantId, UUID workspaceId, UUID customerAccountId, UUID purchaseRequestId,
                 BigDecimal amount, String currency, Instant now);

    void release(UUID tenantId, UUID workspaceId, UUID purchaseRequestId);

    void linkSalesOrder(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, UUID salesOrderId);

    /**
     * v0.14 seam for a Direct Order or any commitment whose PR identity is
     * intentionally absent. Implementations participate in the caller's
     * transaction and must lock the credit account before checking capacity.
     */
    default void reserveForCommitment(UUID tenantId, UUID workspaceId, UUID customerAccountId,
                                      UUID commercialCommitmentId, UUID purchaseRequestId, UUID salesOrderId,
                                      BigDecimal amount, String currency, Instant now) {
        if (purchaseRequestId == null) {
            throw new IllegalStateException("Commitment credit reservation is not configured");
        }
        reserve(tenantId, workspaceId, customerAccountId, purchaseRequestId, amount, currency, now);
    }

    default void releaseForCommitment(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId,
                                      UUID purchaseRequestId) {
        if (purchaseRequestId != null) release(tenantId, workspaceId, purchaseRequestId);
    }

    default void linkSalesOrderForCommitment(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId,
                                             UUID purchaseRequestId, UUID salesOrderId) {
        if (purchaseRequestId != null) linkSalesOrder(tenantId, workspaceId, purchaseRequestId, salesOrderId);
    }
}
