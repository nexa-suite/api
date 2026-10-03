package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.LoadView;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryLoadPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryLoadPersistencePort.AssignDriverCommand;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryLoadPersistencePort.CreateLoadCommand;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryLoadPersistencePort.LoadActionCommand;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryLoadPersistencePort.ReorderStopsCommand;
import com.nexa.api.fulfillmentdelivery.domain.load.DeliveryLoadStatus;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityAttestation;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityEvaluator;
import com.nexa.api.fulfillmentdelivery.domain.load.LoadCompatibilityEvaluator.DeliveryFacts;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.AllocationResult;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Dispatch-authorized commands and assigned-Driver whole-load acceptance. */
@Service
@Profile("!test")
public class DeliveryLoadService {
    private static final int MAX_LOAD_DELIVERIES = 20;
    private static final String CREATE_OPERATION = "DELIVERY_LOAD_CREATE";
    private static final String REORDER_OPERATION = "DELIVERY_LOAD_REORDER";
    private static final String ASSIGN_OPERATION = "DELIVERY_LOAD_ASSIGN";
    private static final String OFFER_OPERATION = "DELIVERY_LOAD_OFFER";
    private static final String CONFIRM_OPERATION = "DELIVERY_LOAD_HANDOFF_CONFIRM";
    private static final String ACCEPT_OPERATION = "DELIVERY_LOAD_ACCEPT";

    private final DeliveryLoadPersistencePort persistence;
    private final DispatchReadinessService readiness;
    private final FulfillmentDriverAssignmentService driverAssignments;
    private final PhysicalAllocationCommands physicalAllocations;
    private final ColdChainPolicyQuery coldChain;
    private final WorkforceDirectory workforce;
    private final WarehouseObjectAccess warehouseAccess;
    private final Clock clock;

    public DeliveryLoadService(DeliveryLoadPersistencePort persistence,
                              DispatchReadinessService readiness,
                              FulfillmentDriverAssignmentService driverAssignments,
                              PhysicalAllocationCommands physicalAllocations,
                              ColdChainPolicyQuery coldChain,
                              WorkforceDirectory workforce,
                              WarehouseObjectAccess warehouseAccess,
                              Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "Delivery-load persistence is required");
        this.readiness = Objects.requireNonNull(readiness, "Dispatch readiness is required");
        this.driverAssignments = Objects.requireNonNull(driverAssignments, "Canonical fulfillment assignments are required");
        this.physicalAllocations = Objects.requireNonNull(physicalAllocations, "Physical allocations are required");
        this.coldChain = Objects.requireNonNull(coldChain, "BC-05 temperature policy query is required");
        this.workforce = Objects.requireNonNull(workforce, "Current logistics workforce is required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Warehouse access is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public List<LoadView> dispatchLoads(CurrentAccessContext context) {
        authorize(context, PermissionKey.DISPATCH_READ);
        Set<UUID> warehouses = warehouseAccess.activeWarehouseIds(context);
        return persistence.listForDispatch(context.tenantId().value(), context.workspaceId().value()).stream()
                .filter(load -> warehouses.contains(load.originWarehouseId())).toList();
    }

    @Transactional(readOnly = true)
    public List<LoadView> driverLoads(CurrentAccessContext context) {
        authorize(context, PermissionKey.DISPATCH_READ);
        return persistence.listForDriver(context.tenantId().value(), context.workspaceId().value(),
                context.membershipId().value());
    }

    @Transactional(readOnly = true)
    public LoadView getDispatchLoad(CurrentAccessContext context, UUID loadId) {
        authorize(context, PermissionKey.DISPATCH_READ);
        LoadView load = find(context, loadId);
        requireWarehouseGrant(context, load);
        return load;
    }

    @Transactional
    public LoadView create(CurrentAccessContext context, DeliveryLoadModels.CreateLoadRequest request,
                           String idempotencyKey) {
        authorize(context, PermissionKey.DISPATCH_SCHEDULE);
        authorize(context, PermissionKey.DISPATCH_READ);
        requireKey(idempotencyKey);
        validateCreate(request);
        Instant now = clock.instant();
        LoadCompatibilityAttestation attestation = request.compatibilityAttestation().toDomain(
                context.membershipId().value(), now);
        List<String> expectedVersions = request.fulfillmentIds().stream()
                .sorted(Comparator.comparing(UUID::toString))
                .map(id -> id + "=" + request.expectedFulfillmentVersions().get(id)).toList();
        String hash = hash("load-create-v1", String.join(",", request.fulfillmentIds().stream()
                        .map(UUID::toString).toList()),
                String.join(",", request.stopOrder().stream().map(UUID::toString).toList()),
                String.join(",", expectedVersions), request.reason(),
                Boolean.toString(attestation.capacitySufficient()), Boolean.toString(attestation.handlingCompatible()),
                Boolean.toString(attestation.zoneReasonable()),
                Boolean.toString(attestation.noExclusiveTransportRestriction()), attestation.observation());
        Optional<LoadView> replay = persistence.findReplay(context.tenantId().value(), context.workspaceId().value(),
                context.membershipId().value(), CREATE_OPERATION, idempotencyKey, hash);
        if (replay.isPresent()) {
            requireWarehouseGrant(context, replay.get());
            return replay.get();
        }

        for (UUID fulfillmentId : request.fulfillmentIds().stream().distinct()
                .sorted(Comparator.comparing(UUID::toString)).toList()) {
            physicalAllocations.lockForFulfillment(context.tenantId().value(), context.workspaceId().value(),
                    fulfillmentId, context.membershipId().value());
        }

        List<DeliveryFacts> locked = orderFacts(request.fulfillmentIds(), persistence.lockCandidateFacts(
                context.tenantId().value(), context.workspaceId().value(), request.fulfillmentIds()));
        Map<UUID, Long> fulfillmentVersions = new LinkedHashMap<>();
        List<DeliveryFacts> evaluatedFacts = new ArrayList<>();
        UUID origin = null;
        for (DeliveryFacts facts : locked) {
            var current = readiness.readiness(context, facts.fulfillmentId());
            if (!current.ready()) throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false);
            AllocationResult allocation = allocation(context, facts.fulfillmentId());
            Set<UUID> warehouses = allocation.lines().stream().map(PhysicalAllocationCommands.Line::warehouseId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            if (allocation.lines().isEmpty() || allocation.lines().stream()
                    .anyMatch(line -> line.warehouseId() == null) || warehouses.size() != 1) {
                throw error("LOAD_COMPATIBILITY_FACTS_UNAVAILABLE", false);
            }
            UUID warehouseId = warehouses.iterator().next();
            if (origin == null) origin = warehouseId;
            evaluatedFacts.add(withOriginAndTemperature(context, withOrigin(facts, warehouseId), current));
            Long expectedVersion = request.expectedFulfillmentVersions().get(facts.fulfillmentId());
            if (expectedVersion == null || expectedVersion != current.fulfillmentVersion()) {
                throw error("CONCURRENCY_CONFLICT", false);
            }
            fulfillmentVersions.put(facts.fulfillmentId(), current.fulfillmentVersion());
        }
        LoadCompatibilityEvaluator.Evaluation evaluation = LoadCompatibilityEvaluator.evaluate(evaluatedFacts);
        if (!evaluation.compatible()) throw compatibilityError(evaluation.reason());
        if (!Objects.equals(origin, evaluation.originWarehouseId()) || !warehouseAccess.hasActiveGrant(context, origin)) {
            throw error("FULFILLMENT_NOT_FOUND", true);
        }

        List<Long> expectedFulfillmentVersions = request.fulfillmentIds().stream()
                .map(fulfillmentVersions::get).toList();
        return persistence.create(new CreateLoadCommand(context.tenantId().value(), context.workspaceId().value(),
                UUID.randomUUID(), context.membershipId().value(), request.fulfillmentIds(), request.stopOrder(),
                expectedFulfillmentVersions, evaluatedFacts, origin, cleanReason(request.reason()), attestation,
                idempotencyKey, hash, now));
    }

    @Transactional
    public LoadView reorder(CurrentAccessContext context, UUID loadId, long expectedVersion,
                            DeliveryLoadModels.ReorderStopsRequest request, String idempotencyKey) {
        authorize(context, PermissionKey.DISPATCH_SCHEDULE);
        requireKey(idempotencyKey);
        if (loadId == null || expectedVersion < 0 || request == null || request.stopOrder() == null) {
            throw error("INVALID_REQUEST", false);
        }
        LoadView current = find(context, loadId);
        requireWarehouseGrant(context, current);
        validateStopOrder(current, request.stopOrder());
        String reason = cleanReason(request.reason());
        String hash = hash("load-reorder-v1", loadId.toString(), Long.toString(expectedVersion),
                String.join(",", request.stopOrder().stream().map(UUID::toString).toList()), reason);
        Optional<LoadView> replay = persistence.findReplay(context.tenantId().value(), context.workspaceId().value(),
                context.membershipId().value(), REORDER_OPERATION, idempotencyKey, hash);
        if (replay.isPresent()) return replay.get();
        return persistence.reorder(new ReorderStopsCommand(context.tenantId().value(), context.workspaceId().value(),
                loadId, context.membershipId().value(), expectedVersion, request.stopOrder(), reason,
                idempotencyKey, hash, clock.instant()));
    }

    @Transactional
    public LoadView assign(CurrentAccessContext context, UUID loadId, long expectedVersion,
                           DeliveryLoadModels.AssignDriverRequest request, String idempotencyKey) {
        authorize(context, PermissionKey.DISPATCH_ASSIGN);
        requireKey(idempotencyKey);
        if (loadId == null || expectedVersion < 0 || request == null || request.driverMembershipId() == null) {
            throw error("INVALID_REQUEST", false);
        }
        LoadView current = find(context, loadId);
        requireWarehouseGrant(context, current);
        var eligible = workforce.findLogisticsAssignees(context.tenantId().value(), context.workspaceId().value())
                .stream().anyMatch(member -> member.id().equals(request.driverMembershipId()));
        if (!eligible) throw error("FULFILLMENT_DRIVER_ASSIGNMENT_REQUIRED", false);
        String vehicle = request.vehicleReference() == null ? null : request.vehicleReference().trim();
        if (vehicle != null && (vehicle.isEmpty() || vehicle.length() > 120)) throw error("INVALID_REQUEST", false);
        String hash = hash("load-assign-v1", loadId.toString(), Long.toString(expectedVersion),
                request.driverMembershipId().toString(), vehicle);
        Optional<LoadView> replay = persistence.findReplay(context.tenantId().value(), context.workspaceId().value(),
                context.membershipId().value(), ASSIGN_OPERATION, idempotencyKey, hash);
        if (replay.isPresent()) return replay.get();
        if (current.version() != expectedVersion) throw error("CONCURRENCY_CONFLICT", false);
        if (current.status() != DeliveryLoadStatus.DRAFT) throw error("FULFILLMENT_TRANSITION_INVALID", false);
        for (DeliveryLoadModels.StopView stop : current.stops().stream()
                .sorted(Comparator.comparingInt(DeliveryLoadModels.StopView::position)).toList()) {
            var ready = readiness.readiness(context, stop.fulfillmentId());
            if (!ready.ready()) throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false);
            AllocationResult allocation = allocation(context, stop.fulfillmentId());
            driverAssignments.assign(context, stop.fulfillmentId(), ready.fulfillmentVersion(),
                    allocation.allocationId(), allocation.version(), request.driverMembershipId(),
                    childAssignmentKey(idempotencyKey, stop.fulfillmentId()));
        }
        return persistence.assign(new AssignDriverCommand(context.tenantId().value(), context.workspaceId().value(),
                loadId, context.membershipId().value(), expectedVersion, request.driverMembershipId(), vehicle,
                idempotencyKey, hash, clock.instant()));
    }

    @Transactional
    public LoadView offer(CurrentAccessContext context, UUID loadId, long expectedVersion, String idempotencyKey) {
        return dispatchAction(context, loadId, expectedVersion, idempotencyKey, PermissionKey.DISPATCH_SCHEDULE,
                "load-offer-v1", OFFER_OPERATION, persistence::offer, null);
    }

    @Transactional
    public LoadView confirmHandoff(CurrentAccessContext context, UUID loadId, long expectedVersion,
                                   String idempotencyKey) {
        return dispatchAction(context, loadId, expectedVersion, idempotencyKey, PermissionKey.DISPATCH_SCHEDULE,
                "load-confirm-v1", CONFIRM_OPERATION, persistence::confirmHandoff,
                load -> { if (load.driverAcceptedAt() != null) requireStopsReady(context, load); });
    }

    @Transactional
    public LoadView accept(CurrentAccessContext context, UUID loadId, long expectedVersion, String idempotencyKey) {
        authorize(context, PermissionKey.DISPATCH_START_ROUTE);
        requireKey(idempotencyKey);
        if (loadId == null || expectedVersion < 0) throw error("INVALID_REQUEST", false);
        LoadView current = find(context, loadId);
        if (!context.membershipId().value().equals(current.assignedDriverMembershipId())) {
            throw error("DELIVERY_NOT_FOUND", true);
        }
        String hash = hash("load-accept-v1", loadId.toString(), Long.toString(expectedVersion));
        Optional<LoadView> replay = persistence.findReplay(context.tenantId().value(), context.workspaceId().value(),
                context.membershipId().value(), ACCEPT_OPERATION, idempotencyKey, hash);
        if (replay.isPresent()) return replay.get();
        if (current.dispatchConfirmedAt() != null) requireStopsReady(context, current);
        return persistence.accept(new LoadActionCommand(context.tenantId().value(), context.workspaceId().value(),
                loadId, context.membershipId().value(), expectedVersion, idempotencyKey, hash, clock.instant()));
    }

    private LoadView dispatchAction(CurrentAccessContext context, UUID loadId, long expectedVersion,
                                    String idempotencyKey, PermissionKey permission, String hashVersion,
                                    String operation, Action action, Consumer<LoadView> finalizationGuard) {
        authorize(context, permission);
        requireKey(idempotencyKey);
        if (loadId == null || expectedVersion < 0) throw error("INVALID_REQUEST", false);
        LoadView current = find(context, loadId);
        requireWarehouseGrant(context, current);
        String hash = hash(hashVersion, loadId.toString(), Long.toString(expectedVersion));
        Optional<LoadView> replay = persistence.findReplay(context.tenantId().value(), context.workspaceId().value(),
                context.membershipId().value(), operation, idempotencyKey, hash);
        if (replay.isPresent()) return replay.get();
        if (finalizationGuard != null) finalizationGuard.accept(current);
        return action.run(new LoadActionCommand(context.tenantId().value(), context.workspaceId().value(), loadId,
                context.membershipId().value(), expectedVersion, idempotencyKey, hash, clock.instant()));
    }

    private void requireStopsReady(CurrentAccessContext context, LoadView load) {
        for (DeliveryLoadModels.StopView stop : load.stops()) {
            if (!readiness.readiness(context, stop.fulfillmentId()).ready()) {
                throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false);
            }
        }
    }

    private AllocationResult allocation(CurrentAccessContext context, UUID fulfillmentId) {
        try {
            AllocationResult allocation = physicalAllocations.getByFulfillment(context.tenantId().value(),
                    context.workspaceId().value(), fulfillmentId, context.membershipId().value());
            if (allocation == null || !"ALLOCATED".equals(allocation.status())) {
                throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false);
            }
            return allocation;
        } catch (WarehouseOperationException exception) {
            if (exception.notFound()) throw error("FULFILLMENT_NOT_FOUND", true);
            throw exception;
        }
    }

    private LoadView find(CurrentAccessContext context, UUID loadId) {
        if (loadId == null) throw error("DELIVERY_NOT_FOUND", true);
        return persistence.find(context.tenantId().value(), context.workspaceId().value(), loadId)
                .orElseThrow(() -> error("DELIVERY_NOT_FOUND", true));
    }

    private void requireWarehouseGrant(CurrentAccessContext context, LoadView load) {
        if (load.originWarehouseId() == null || !warehouseAccess.hasActiveGrant(context, load.originWarehouseId())) {
            throw error("DELIVERY_NOT_FOUND", true);
        }
    }

    private static List<DeliveryFacts> orderFacts(List<UUID> fulfillmentIds, List<DeliveryFacts> facts) {
        Map<UUID, DeliveryFacts> byId = new LinkedHashMap<>();
        for (DeliveryFacts fact : facts) byId.put(fact.fulfillmentId(), fact);
        if (facts.size() != fulfillmentIds.size() || fulfillmentIds.stream().anyMatch(id -> !byId.containsKey(id))) {
            throw error("FULFILLMENT_NOT_FOUND", true);
        }
        return fulfillmentIds.stream().map(byId::get).toList();
    }

    private static DeliveryFacts withOrigin(DeliveryFacts fact, UUID origin) {
        return new DeliveryFacts(fact.deliveryId(), fact.deliveryVersion(), fact.fulfillmentId(),
                fact.fulfillmentVersion(), fact.fulfillmentStatus(), fact.deliveryStatus(), origin,
                fact.windowStart(), fact.windowEnd(), fact.temperatureMin(), fact.temperatureMax(),
                fact.temperatureUnit(), fact.temperatureStatus(), fact.dispatchOrderStatus(),
                fact.fulfillmentHeld(), fact.unresolvedBlockingOrCriticalIncident(),
                fact.structuredExclusiveTransportRestriction(), fact.hasExistingDriverAssignment(), fact.hasExistingLoad());
    }

    private DeliveryFacts withOriginAndTemperature(CurrentAccessContext context, DeliveryFacts facts,
                                                   com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels.Readiness current) {
        BigDecimal minimum = null;
        BigDecimal maximum = null;
        boolean skuHasTemperatureBounds = false;
        for (var line : current.lines()) {
            if (line.skuId() == null) throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false);
            var policy = coldChain.temperatureRequirementForSku(context.tenantId().value(), context.workspaceId().value(),
                            line.skuId())
                    .orElseThrow(() -> error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false));
            if (!"ACTIVE".equals(policy.status())) {
                throw error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false);
            }
            if (policy.temperatureMin() == null && policy.temperatureMax() == null) continue;
            if (policy.temperatureMin() == null || policy.temperatureMax() == null
                    || policy.temperatureMin().compareTo(policy.temperatureMax()) > 0) {
                throw error("LOAD_COMPATIBILITY_FACTS_INVALID", false);
            }
            skuHasTemperatureBounds = true;
            minimum = minimum == null ? policy.temperatureMin() : minimum.max(policy.temperatureMin());
            maximum = maximum == null ? policy.temperatureMax() : maximum.min(policy.temperatureMax());
        }
        if (skuHasTemperatureBounds && (minimum == null || maximum == null || minimum.compareTo(maximum) > 0)) {
            throw error("LOAD_COMPATIBILITY_FACTS_INVALID", false);
        }

        boolean legacyHasBounds = facts.temperatureMin() != null || facts.temperatureMax() != null;
        if (legacyHasBounds && (facts.temperatureMin() == null || facts.temperatureMax() == null
                || facts.temperatureUnit() == null
                || !("CELSIUS".equalsIgnoreCase(facts.temperatureUnit()) || "C".equalsIgnoreCase(facts.temperatureUnit()))
                || facts.temperatureMin().compareTo(facts.temperatureMax()) > 0)) {
            throw error("LOAD_COMPATIBILITY_FACTS_INVALID", false);
        }
        if (skuHasTemperatureBounds) {
            if (legacyHasBounds && (facts.temperatureMin().compareTo(minimum) != 0
                    || facts.temperatureMax().compareTo(maximum) != 0)) {
                throw error("LOAD_COMPATIBILITY_FACTS_INVALID", false);
            }
        } else if (legacyHasBounds) {
            // A persisted legacy dispatch temperature is an explicit operational fact. Preserve it
            // even when Catalog declares no SKU-specific temperature requirement.
            minimum = facts.temperatureMin();
            maximum = facts.temperatureMax();
        } else {
            // Paired-null active SKU bounds mean an explicit absence of a SKU constraint. Where BC-05
            // has a single common warehouse range, use it as the current operational temperature profile.
            var warehouseRange = coldChain.commonTemperatureRangeForWarehouse(context.tenantId().value(),
                    context.workspaceId().value(), facts.originWarehouseId());
            if (warehouseRange.isPresent()) {
                minimum = warehouseRange.get().minimumCelsius();
                maximum = warehouseRange.get().maximumCelsius();
                if (minimum == null || maximum == null || minimum.compareTo(maximum) > 0) {
                    throw error("LOAD_COMPATIBILITY_FACTS_INVALID", false);
                }
            }
        }
        String unit = minimum == null && maximum == null ? "NONE" : "CELSIUS";
        return new DeliveryFacts(facts.deliveryId(), facts.deliveryVersion(), facts.fulfillmentId(),
                facts.fulfillmentVersion(), facts.fulfillmentStatus(), facts.deliveryStatus(), facts.originWarehouseId(),
                facts.windowStart(), facts.windowEnd(), minimum, maximum, unit, facts.temperatureStatus(),
                facts.dispatchOrderStatus(), facts.fulfillmentHeld(), facts.unresolvedBlockingOrCriticalIncident(),
                facts.structuredExclusiveTransportRestriction(), facts.hasExistingDriverAssignment(), facts.hasExistingLoad());
    }

    private static void validateCreate(DeliveryLoadModels.CreateLoadRequest request) {
        if (request == null || request.fulfillmentIds() == null || request.stopOrder() == null
                || request.expectedFulfillmentVersions() == null
                || request.fulfillmentIds().size() < 2 || request.fulfillmentIds().size() > MAX_LOAD_DELIVERIES
                || request.fulfillmentIds().stream().anyMatch(Objects::isNull)
                || request.stopOrder().stream().anyMatch(Objects::isNull)
                || request.compatibilityAttestation() == null) throw error("INVALID_REQUEST", false);
        if (request.reason() == null || request.reason().isBlank() || request.reason().trim().length() > 500) {
            throw error("INVALID_REQUEST", false);
        }
        DeliveryLoadModels.CompatibilityAttestationRequest attestation = request.compatibilityAttestation();
        if (!attestation.capacitySufficient() || !attestation.handlingCompatible() || !attestation.zoneReasonable()
                || !attestation.noExclusiveTransportRestriction()
                || attestation.observation() != null && (attestation.observation().isBlank()
                || attestation.observation().trim().length() > 1000)) throw error("INVALID_REQUEST", false);
        if (request.fulfillmentIds().stream().distinct().count() != request.fulfillmentIds().size()) {
            throw error("INVALID_REQUEST", false);
        }
        if (!Set.copyOf(request.fulfillmentIds()).equals(Set.copyOf(request.stopOrder()))
                || request.stopOrder().size() != request.fulfillmentIds().size()
                || !Set.copyOf(request.fulfillmentIds()).equals(request.expectedFulfillmentVersions().keySet())
                || request.expectedFulfillmentVersions().values().stream()
                .anyMatch(version -> version == null || version < 0)) throw error("INVALID_REQUEST", false);
    }

    private static void validateStopOrder(LoadView load, List<UUID> stopOrder) {
        List<UUID> current = load.stops().stream().map(DeliveryLoadModels.StopView::fulfillmentId).toList();
        if (stopOrder.stream().anyMatch(Objects::isNull) || stopOrder.size() != current.size()
                || !Set.copyOf(stopOrder).equals(Set.copyOf(current))) throw error("INVALID_REQUEST", false);
    }

    private static String cleanReason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) throw error("INVALID_REQUEST", false);
        return reason.trim();
    }

    private static void authorize(CurrentAccessContext context, PermissionKey permission) {
        Objects.requireNonNull(context, "Verified access context is required").requirePermission(permission);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.trim().length() > 160) throw error("IDEMPOTENCY_KEY_REQUIRED", false);
    }

    private static FulfillmentOperationException compatibilityError(String reason) {
        if ("DELIVERY_OPERATIONAL_HOLD".equals(reason)) return error("DELIVERY_OPERATIONAL_EXCEPTION_BLOCKING", false);
        if ("DELIVERY_NOT_READY_FOR_LOAD".equals(reason)) return error("FULFILLMENT_NOT_READY_FOR_DISPATCH", false);
        if ("DELIVERY_ALREADY_ASSIGNED".equals(reason)) return error("FULFILLMENT_DRIVER_ALREADY_ASSIGNED", false);
        return error("FULFILLMENT_TRANSITION_INVALID", false);
    }

    private static String hash(String version, String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, version);
            for (String value : values) update(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String childAssignmentKey(String parentKey, UUID fulfillmentId) {
        return "delivery-load-driver-" + hash("delivery-load-driver-assignment-v1", parentKey, fulfillmentId.toString());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = Objects.toString(value, "").getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    @FunctionalInterface
    private interface Action {
        LoadView run(LoadActionCommand command);
    }
}
