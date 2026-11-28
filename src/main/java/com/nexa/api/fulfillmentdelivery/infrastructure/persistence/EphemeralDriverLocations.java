package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.Coordinate;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.SampleCommand;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService.error;

/** Bounded, process-local latest samples. Never persists coordinates. */
@org.springframework.stereotype.Component
@org.springframework.context.annotation.Profile("tenant-cloud")
public final class EphemeralDriverLocations {
    private static final int MAX_DRIVERS = 4096;
    private final Map<Key, Sample> samples = new HashMap<>();
    private record Key(UUID tenant, UUID workspace, UUID actor) { }
    public record Sample(UUID workday, Coordinate coordinate, Set<UUID> deliveries) {
        public Sample { deliveries = Set.copyOf(deliveries); }
    }

    public synchronized Coordinate capture(SampleCommand request, Set<UUID> deliveryIds) {
        Instant now = request.now();
        samples.values().removeIf(sample -> !sample.coordinate().expiresAt().isAfter(now));
        var key = new Key(request.tenantId(), request.workspaceId(), request.actorMembershipId());
        var incoming = request.coordinate();
        Instant capturedAt = incoming.capturedAt().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = capturedAt.plusSeconds(120);
        if (!expiresAt.isAfter(now)) throw error("DRIVER_COORDINATE_INVALID", false);
        var coordinate = new Coordinate(incoming.sampleId(), incoming.latitude(), incoming.longitude(),
                incoming.accuracyMeters(), capturedAt, expiresAt);
        for (var entry : samples.entrySet()) {
            Sample prior = entry.getValue();
            if (entry.getKey().tenant().equals(key.tenant()) && entry.getKey().workspace().equals(key.workspace())
                    && prior.coordinate().sampleId().equals(coordinate.sampleId())) {
                if (!entry.getKey().equals(key) || !prior.workday().equals(request.workdayId())
                        || !prior.coordinate().equals(coordinate)) throw error("IDEMPOTENCY_PAYLOAD_CONFLICT", false);
                return prior.coordinate();
            }
        }
        Sample previous = samples.get(key);
        if (previous == null && samples.size() >= MAX_DRIVERS) throw error("DRIVER_LOCATION_UNAVAILABLE", false);
        if (previous == null || !coordinate.capturedAt().isBefore(previous.coordinate().capturedAt())) {
            samples.put(key, new Sample(request.workdayId(), coordinate, deliveryIds));
        }
        return coordinate;
    }

    public synchronized Sample latest(UUID tenant, UUID workspace, UUID actor, Instant now) {
        var key = new Key(tenant, workspace, actor);
        var sample = samples.get(key);
        if (sample != null && !sample.coordinate().expiresAt().isAfter(now)) {
            samples.remove(key);
            return null;
        }
        return sample;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
    public synchronized void discardExpired() {
        Instant now = Instant.now();
        samples.values().removeIf(sample -> !sample.coordinate().expiresAt().isAfter(now));
    }

    public synchronized void clear(UUID tenant, UUID workspace, UUID actor) {
        samples.remove(new Key(tenant, workspace, actor));
    }
}
