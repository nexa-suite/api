package com.nexa.api.fulfillmentdelivery.application.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public final class DriverTrackingModels {
    private DriverTrackingModels() { }
    public record Workday(UUID id, long version, String status, Instant startedAt, Instant endedAt,
                          boolean locationAvailable) { }
    public record Coordinate(UUID sampleId, double latitude, double longitude, double accuracyMeters,
                             Instant capturedAt, Instant expiresAt) { }
    public record DeliveryTracking(UUID deliveryId, @Schema(nullable = true) Coordinate location) { }
    public record WorkdayCommand(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                 UUID workdayId, Long expectedVersion, String action,
                                 String idempotencyKey, String requestHash, Instant now, boolean locationAvailableAtStart) {
        public WorkdayCommand(UUID tenantId, UUID workspaceId, UUID actorMembershipId, UUID workdayId,
                              Long expectedVersion, String action, String idempotencyKey, String requestHash, Instant now) {
            this(tenantId, workspaceId, actorMembershipId, workdayId, expectedVersion, action, idempotencyKey, requestHash, now, true);
        }
    }
    public record SampleCommand(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                UUID workdayId, Coordinate coordinate, Instant now) { }
    public record DeliveryScope(UUID salesOrderId, UUID driverMembershipId, String status, Instant trackingStartedAt) {
        public DeliveryScope(UUID salesOrderId, UUID driverMembershipId, String status) {
            this(salesOrderId, driverMembershipId, status, null);
        }
    }
}
