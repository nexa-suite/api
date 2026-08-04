package com.nexa.api.shared.events;

import java.util.Optional;
import java.util.UUID;

/**
 * Event ACL for the payment published language. Kept in shared events because
 * this V1 handoff is read-only and must not widen the Payments write surface.
 */
public interface PaymentEventContextQueryPort {
    Optional<UUID> findClientAccountId(UUID tenantId, UUID workspaceId, String aggregateType, UUID aggregateId);
}
