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
}
