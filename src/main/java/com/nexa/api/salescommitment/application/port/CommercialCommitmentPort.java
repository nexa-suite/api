package com.nexa.api.salescommitment.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /** Releases backing and any still-reserved credit when a pending Sales Order is cancelled or rejected. */
    default void releaseForSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId, String reason) {
        throw new UnsupportedOperationException("Sales Order commitment release is not configured");
    }

    /** Closes a Direct Order commitment when a pending prepaid order is confirmed. */
    default void confirmDirectOrder(UUID tenantId, UUID workspaceId, UUID commitmentId, UUID salesOrderId) {
        throw new UnsupportedOperationException("Direct Order commitment confirmation is not configured");
    }

    /** Atomic Direct Order command. No Purchase Request is fabricated. */
    default DirectOrderResult establishDirectOrder(DirectOrderCommand command) {
        throw new UnsupportedOperationException("Direct Order is not configured");
    }

    record DirectOrderCommand(UUID tenantId, UUID workspaceId, UUID clientAccountId, UUID buyerMembershipId,
                              UUID actorMembershipId, String priority, LocalDate requestedDeliveryDate,
                              String deliverySnapshot, String paymentOption, String notes, Instant now,
                              List<DirectOrderLine> lines, String idempotencyKey, String requestHash) {
        public DirectOrderCommand {
            lines = List.copyOf(lines == null ? List.of() : lines);
        }
    }

    record DirectOrderLine(String catalogItemId, BigDecimal quantity, String unit) { }

    record DirectOrderResult(UUID commitmentId, UUID salesOrderId, String orderNumber, long version) { }
}
