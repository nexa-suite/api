package com.nexa.api.fulfillmentdelivery.application.model;

import com.nexa.api.fulfillmentdelivery.domain.load.DeliveryLoadStatus;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityAttestation;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Transport-independent current view and commands for a grouped Delivery load. */
public final class DeliveryLoadModels {
    private DeliveryLoadModels() { }

    public record CreateLoadRequest(List<UUID> fulfillmentIds,
                                    List<UUID> stopOrder,
                                    Map<UUID, Long> expectedFulfillmentVersions,
                                    String reason,
                                    CompatibilityAttestationRequest compatibilityAttestation) {
        public CreateLoadRequest {
            fulfillmentIds = immutableNullableElements(fulfillmentIds);
            stopOrder = immutableNullableElements(stopOrder);
            expectedFulfillmentVersions = expectedFulfillmentVersions == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(expectedFulfillmentVersions));
        }
    }

    public record CompatibilityAttestationRequest(boolean capacitySufficient,
                                                   boolean handlingCompatible,
                                                   boolean zoneReasonable,
                                                   boolean noExclusiveTransportRestriction,
                                                   String observation) {
        public LoadCompatibilityAttestation toDomain(UUID actorMembershipId, Instant at) {
            return new LoadCompatibilityAttestation(capacitySufficient, handlingCompatible, zoneReasonable,
                    noExclusiveTransportRestriction, actorMembershipId, at, observation);
        }
    }

    public record ReorderStopsRequest(List<UUID> stopOrder, String reason) {
        public ReorderStopsRequest {
            stopOrder = immutableNullableElements(stopOrder);
        }
    }

    public record AssignDriverRequest(UUID driverMembershipId, String vehicleReference) { }

    public record StopView(UUID fulfillmentId, UUID deliveryId, int position, long deliveryVersion) { }

    public record CompatibilityAttestationView(boolean capacitySufficient,
                                               boolean handlingCompatible,
                                               boolean zoneReasonable,
                                               boolean noExclusiveTransportRestriction,
                                               UUID attestedByMembershipId,
                                               Instant attestedAt,
                                               String observation) {
        public static CompatibilityAttestationView from(LoadCompatibilityAttestation value) {
            return value == null ? null : new CompatibilityAttestationView(value.capacitySufficient(),
                    value.handlingCompatible(), value.zoneReasonable(), value.noExclusiveTransportRestriction(),
                    value.attestedByMembershipId(), value.attestedAt(), value.observation());
        }
    }

    public record HistoryEvent(String eventType,
                               UUID actorMembershipId,
                               Instant occurredAt,
                               String reason,
                               UUID affectedDriverMembershipId,
                               List<UUID> previousStopOrder,
                               List<UUID> newStopOrder,
                               CompatibilityAttestationView compatibilityAttestation) {
        public HistoryEvent {
            previousStopOrder = previousStopOrder == null ? List.of() : List.copyOf(previousStopOrder);
            newStopOrder = newStopOrder == null ? List.of() : List.copyOf(newStopOrder);
        }
    }

    public record LoadView(UUID id,
                           long version,
                           DeliveryLoadStatus status,
                           UUID originWarehouseId,
                           List<StopView> stops,
                           UUID assignedDriverMembershipId,
                           String vehicleReference,
                           CompatibilityAttestationView compatibilityAttestation,
                           UUID offeredByMembershipId,
                           Instant offeredAt,
                           UUID dispatchConfirmedByMembershipId,
                           Instant dispatchConfirmedAt,
                           UUID driverAcceptedByMembershipId,
                           Instant driverAcceptedAt,
                           List<HistoryEvent> history) {
        public LoadView {
            stops = List.copyOf(stops == null ? List.of() : stops);
            history = List.copyOf(history == null ? List.of() : history);
        }
    }

    private static <T> List<T> immutableNullableElements(List<T> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
