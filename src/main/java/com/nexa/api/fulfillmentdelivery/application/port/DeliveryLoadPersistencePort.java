package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.LoadView;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityAttestation;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityEvaluator.DeliveryFacts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** BC-06-owned persistence contract for current Delivery-load state and append-only history. */
public interface DeliveryLoadPersistencePort {
    List<DeliveryFacts> lockCandidateFacts(UUID tenantId, UUID workspaceId, List<UUID> fulfillmentIds);

    List<LoadView> listForDispatch(UUID tenantId, UUID workspaceId);

    List<LoadView> listForDriver(UUID tenantId, UUID workspaceId, UUID driverMembershipId);

    Optional<LoadView> find(UUID tenantId, UUID workspaceId, UUID loadId);

    Optional<LoadView> findReplay(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                                  String operation, String idempotencyKey, String requestHash);

    LoadView create(CreateLoadCommand command);

    LoadView reorder(ReorderStopsCommand command);

    LoadView assign(AssignDriverCommand command);

    LoadView offer(LoadActionCommand command);

    LoadView confirmHandoff(LoadActionCommand command);

    LoadView accept(LoadActionCommand command);

    record CreateLoadCommand(UUID tenantId,
                             UUID workspaceId,
                             UUID loadId,
                             UUID actorMembershipId,
                             List<UUID> fulfillmentIds,
                             List<UUID> stopOrder,
                             List<Long> expectedFulfillmentVersions,
                             List<DeliveryFacts> compatibilityFacts,
                             UUID originWarehouseId,
                             String reason,
                             LoadCompatibilityAttestation compatibilityAttestation,
                             String idempotencyKey,
                             String requestHash,
                             Instant occurredAt) {
        public CreateLoadCommand {
            fulfillmentIds = List.copyOf(fulfillmentIds);
            stopOrder = List.copyOf(stopOrder);
            expectedFulfillmentVersions = List.copyOf(expectedFulfillmentVersions);
            compatibilityFacts = List.copyOf(compatibilityFacts);
        }
    }

    record ReorderStopsCommand(UUID tenantId, UUID workspaceId, UUID loadId, UUID actorMembershipId,
                               long expectedVersion, List<UUID> stopOrder, String reason,
                               String idempotencyKey, String requestHash, Instant occurredAt) {
        public ReorderStopsCommand { stopOrder = List.copyOf(stopOrder); }
    }

    record AssignDriverCommand(UUID tenantId, UUID workspaceId, UUID loadId, UUID actorMembershipId,
                               long expectedVersion, UUID driverMembershipId, String vehicleReference,
                               String idempotencyKey, String requestHash, Instant occurredAt) { }

    record LoadActionCommand(UUID tenantId, UUID workspaceId, UUID loadId, UUID actorMembershipId,
                             long expectedVersion, String idempotencyKey, String requestHash,
                             Instant occurredAt) { }
}
