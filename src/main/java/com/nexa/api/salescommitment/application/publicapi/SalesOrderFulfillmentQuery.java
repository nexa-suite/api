package com.nexa.api.salescommitment.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only Sales Commitment contract consumed by Fulfillment. */
public interface SalesOrderFulfillmentQuery {
    Snapshot get(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    /** Same snapshot while holding the Sales Commitment aggregate row lock. */
    Snapshot getForUpdate(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    record Snapshot(UUID id, String number, UUID clientAccountId, String status,
                    String paymentOption, UUID commercialCommitmentId,
                    String destinationSnapshot, String currency, BigDecimal total,
                    long version, List<Line> lines) {
        public Snapshot { lines = List.copyOf(lines == null ? List.of() : lines); }
    }

    record Line(UUID id, UUID skuId, String catalogItemId, BigDecimal quantity, String unit,
                BigDecimal unitPriceAmount, String currency) { }
}
