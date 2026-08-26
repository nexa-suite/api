package com.nexa.api.salescommitment.application.port.out;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Published-language read boundary used by asynchronous cross-context workflows.
 * It exposes sales facts without leaking sales persistence rows or SQL.
 */
public interface SalesEventContextQueryPort {
    Optional<PurchaseRequestSnapshot> findPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId);

    Optional<SalesOrderSnapshot> findSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    Optional<SalesOrderSnapshot> findSalesOrderBySourcePurchaseRequest(UUID tenantId, UUID workspaceId,
                                                                         UUID purchaseRequestId);

    Set<UUID> findBuyerMembershipIds(UUID tenantId, UUID workspaceId, UUID clientAccountId);

    record PurchaseRequestSnapshot(UUID id, UUID clientAccountId, long version) {
        public PurchaseRequestSnapshot {
            if (id == null || clientAccountId == null) throw new IllegalArgumentException("Sales snapshot ids are required");
            if (version < 0) throw new IllegalArgumentException("Sales snapshot version cannot be negative");
        }
    }

    record SalesOrderSnapshot(UUID id, UUID clientAccountId, long version) {
        public SalesOrderSnapshot {
            if (id == null || clientAccountId == null) throw new IllegalArgumentException("Sales snapshot ids are required");
            if (version < 0) throw new IllegalArgumentException("Sales snapshot version cannot be negative");
        }
    }
}
