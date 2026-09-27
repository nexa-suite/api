package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.util.UUID;

/** Required authoritative commercial and payment facts for a financial correction. */
public interface FinancialAdjustmentSource {
    Snapshot claimSalesOrderCorrection(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    boolean hasSuccessfulPayment(UUID tenantId, UUID workspaceId, UUID salesOrderId);
    record Snapshot(String status, String currency, BigDecimal total) { }
}
