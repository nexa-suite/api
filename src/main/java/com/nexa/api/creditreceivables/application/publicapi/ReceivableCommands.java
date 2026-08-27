package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BC-07 write boundary for an already-confirmed commercial subject. */
public interface ReceivableCommands {
    UUID postForSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId,
                           UUID clientAccountId, BigDecimal amount, String currency, Instant now);
}
