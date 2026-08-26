package com.nexa.api.payments.application.publicapi;

import java.util.UUID;

/** Narrow Payments-owned read contract for PREPAID commercial confirmation. */
public interface PaymentConfirmationQuery {
    boolean isConfirmed(UUID tenantId, UUID workspaceId, UUID salesOrderId);
}
