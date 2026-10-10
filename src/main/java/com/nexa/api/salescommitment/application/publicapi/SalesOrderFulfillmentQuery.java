package com.nexa.api.salescommitment.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only Sales Commitment contract consumed by Fulfillment. */
public interface SalesOrderFulfillmentQuery {
    Snapshot get(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    /** Same snapshot while holding the Sales Commitment aggregate row lock. */
    Snapshot getForUpdate(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    /** Batch header facts for owner-scoped fulfillment projections. */
    Map<UUID, Header> findHeaders(UUID tenantId, UUID workspaceId, List<UUID> salesOrderIds);

    /**
     * Bounded sales-order references scoped by the owning Buyer account.
     * Results are ordered by creation time descending, then ID ascending.
     */
    OrderReferencePage findReferencesByClientAccount(UUID tenantId, UUID workspaceId,
                                                      UUID clientAccountId, int page, int size);

    record Header(UUID id, String number, UUID clientAccountId, String priority) { }

    record OrderReference(UUID id, String number, Instant createdAt) { }

    record OrderReferencePage(List<OrderReference> items, int page, int size, boolean hasMore) {
        public OrderReferencePage {
            items = List.copyOf(items == null ? List.of() : items);
        }
    }

    record Snapshot(UUID id, String number, UUID clientAccountId, String status,
                    String paymentOption, UUID commercialCommitmentId,
                    String destinationSnapshot, String currency, BigDecimal total,
                    long version, List<Line> lines) {
        public Snapshot { lines = List.copyOf(lines == null ? List.of() : lines); }
    }

    record Line(UUID id, UUID skuId, String catalogItemId, BigDecimal quantity, String unit,
                BigDecimal unitPriceAmount, String currency) { }
}
