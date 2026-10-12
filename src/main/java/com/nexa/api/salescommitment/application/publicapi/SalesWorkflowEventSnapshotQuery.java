package com.nexa.api.salescommitment.application.publicapi;

import java.util.Optional;
import java.util.UUID;

/** Immutable Sales facts needed to handle an already-published workflow event. */
public interface SalesWorkflowEventSnapshotQuery {
    Optional<PurchaseRequestSnapshot> findPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId);

    Optional<SalesOrderSnapshot> findSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    Optional<SalesOrderSnapshot> findSalesOrderBySourcePurchaseRequest(UUID tenantId, UUID workspaceId,
                                                                        UUID purchaseRequestId);

    record PurchaseRequestSnapshot(UUID id, UUID clientAccountId, long version) {
        public PurchaseRequestSnapshot {
            if (id == null || clientAccountId == null || version < 0) {
                throw new IllegalArgumentException("Sales purchase-request event snapshot is incomplete");
            }
        }
    }

    record SalesOrderSnapshot(UUID id, UUID clientAccountId, long version, UUID commercialCommitmentId) {
        public SalesOrderSnapshot {
            if (id == null || clientAccountId == null || version < 0) {
                throw new IllegalArgumentException("Sales order event snapshot is incomplete");
            }
        }
    }
}
