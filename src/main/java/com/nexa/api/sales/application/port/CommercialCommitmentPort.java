package com.nexa.api.sales.application.port;

import java.util.UUID;

/**
 * Least-irreversible seam for the V1 commercial commitment lifecycle.
 *
 * The strategic ownership decision remains open; this port keeps the
 * submitted Purchase Request behavior explicit without restructuring modules.
 */
public interface CommercialCommitmentPort {
    void activateForPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId);

    void releaseForPurchaseRequest(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, String reason);

    void convertForSalesOrder(UUID tenantId, UUID workspaceId, UUID purchaseRequestId, UUID salesOrderId);
}
