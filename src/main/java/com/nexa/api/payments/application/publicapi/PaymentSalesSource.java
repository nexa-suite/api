package com.nexa.api.payments.application.publicapi;

import java.math.BigDecimal;
import java.util.UUID;

/** Authoritative commercial facts needed to prepare or reconcile a payment. */
public interface PaymentSalesSource {
    Snapshot claimPayableSubject(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    boolean exists(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    record Snapshot(UUID clientAccountId, BigDecimal total, String currency, String status, String paymentOption) { }
}
