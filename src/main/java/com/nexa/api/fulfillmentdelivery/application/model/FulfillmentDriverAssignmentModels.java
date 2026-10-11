package com.nexa.api.fulfillmentdelivery.application.model;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;
import java.util.UUID;

/** Current and historical driver assignment view for prepared Fulfillment. */
public final class FulfillmentDriverAssignmentModels {
    private FulfillmentDriverAssignmentModels() { }

    @NamedInterface("fulfillment-public")
    public record AssignmentView(UUID id, UUID fulfillmentId, long fulfillmentVersion,
                                 UUID physicalAllocationId, long physicalAllocationVersion,
                                 UUID responsibleMembershipId, String responsibleDisplayName,
                                 UUID actorMembershipId, Instant assignedAt, Instant plannedDispatchAt,
                                 UUID deliveryId, boolean current) { }
}
