package com.nexa.api.payments.application.publicapi;

import java.util.UUID;

/** Narrow Payments-owned read contract for PREPAID commercial confirmation. */
public interface PaymentConfirmationQuery {
    boolean isConfirmed(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    /** True when at least one successful payment is durably recorded for the Sales Order. */
    default boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return isConfirmed(tenantId, workspaceId, salesOrderId);
    }
}
