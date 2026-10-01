package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgeRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.AcknowledgementResult;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.DispatchInstructionScope;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.InstructionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryInstructionModels.PublishedInstruction;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryInstructionPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.instruction.DeliveryInstructionKind;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application authority for dispatch instructions and assigned-driver acknowledgements. */
@Service
@Profile("!test")
public class DeliveryInstructionService {
    private final DeliveryInstructionPersistencePort persistence;
    private final DispatchReadinessService readiness;
    private final Clock clock;

    public DeliveryInstructionService(DeliveryInstructionPersistencePort persistence,
                                      DispatchReadinessService readiness, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "Delivery-instruction persistence is required");
        this.readiness = Objects.requireNonNull(readiness, "Dispatch-readiness authority is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public InstructionSetView getForDriver(CurrentAccessContext context, UUID deliveryId) {
        context.requirePermission(PermissionKey.DISPATCH_READ);
        if (deliveryId == null) throw invalid("DELIVERY_NOT_FOUND");
        return persistence.findForDriver(tenant(context), workspace(context), actor(context), deliveryId);
    }

    @Transactional(readOnly = true)
    public InstructionSetView getForDispatch(CurrentAccessContext context, UUID deliveryId) {
        context.requirePermission(PermissionKey.DISPATCH_READ);
        if (deliveryId == null) throw invalid("DELIVERY_NOT_FOUND");
        DispatchInstructionScope scope = persistence.findDispatchScope(tenant(context), workspace(context), deliveryId)
                .orElseThrow(() -> invalid("DELIVERY_NOT_FOUND"));
        if (scope.fulfillmentId() == null) throw invalid("DELIVERY_NOT_FOUND");
        // Reuse BC-06's current dispatch-read and Warehouse-grant authority used by publishing.
        readiness.readiness(context, scope.fulfillmentId());
        return persistence.findForDispatch(tenant(context), workspace(context), deliveryId);
    }

    @Transactional
    public AcknowledgementResult acknowledge(CurrentAccessContext context, UUID deliveryId,
                                             long expectedInstructionSetVersion,
                                             List<UUID> instructionIds, String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        requireKey(idempotencyKey);
        if (deliveryId == null) throw invalid("DELIVERY_NOT_FOUND");
        if (expectedInstructionSetVersion < 0) throw invalid("VERSION_INVALID");
        if (instructionIds == null || instructionIds.isEmpty() || instructionIds.size() > 100
                || instructionIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(instructionIds).size() != instructionIds.size()) {
            throw invalid("INVALID_REQUEST");
        }
        List<UUID> ids = List.copyOf(instructionIds);
        List<UUID> canonicalIds = ids.stream().sorted().toList();
        String canonical = canonicalIds.stream().map(UUID::toString).reduce((left, right) -> left + "," + right).orElse("");
        String requestHash = hash("delivery-instruction-ack-v1|" + deliveryId + "|"
                + expectedInstructionSetVersion + "|" + canonical);
        return persistence.acknowledge(new AcknowledgeRequest(tenant(context), workspace(context), deliveryId,
                actor(context), expectedInstructionSetVersion, ids, idempotencyKey, requestHash, clock.instant()));
    }

    @Transactional
    public PublishedInstruction publish(CurrentAccessContext context, UUID deliveryId,
                                        long expectedDeliveryVersion, UUID instructionId,
                                        DeliveryInstructionKind kind, String content, String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_SCHEDULE);
        requireKey(idempotencyKey);
        if (deliveryId == null || kind == null || content == null || content.isBlank() || content.length() > 2000) {
            throw invalid("INVALID_REQUEST");
        }
        if (expectedDeliveryVersion < 0) throw invalid("VERSION_INVALID");

        DispatchInstructionScope scope = persistence.findDispatchScope(tenant(context), workspace(context), deliveryId)
                .orElseThrow(() -> invalid("DELIVERY_NOT_FOUND"));
        if (scope.fulfillmentId() == null) throw invalid("DELIVERY_NOT_FOUND");
        // Reuse BC-06's current dispatch-read and Warehouse-grant authority before any idempotent replay.
        readiness.readiness(context, scope.fulfillmentId());

        String normalizedContent = content.trim();
        String requestHash = hash("delivery-instruction-publish-v1|" + deliveryId + "|"
                + expectedDeliveryVersion + "|" + Objects.toString(instructionId, "new") + "|"
                + kind.name() + "|" + normalizedContent);
        return persistence.publish(new PublishRequest(tenant(context), workspace(context), deliveryId,
                instructionId, kind, normalizedContent, actor(context), expectedDeliveryVersion,
                idempotencyKey, requestHash, clock.instant()));
    }

    private static void requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw invalid("IDEMPOTENCY_KEY_REQUIRED");
        }
    }

    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }
    private static UUID actor(CurrentAccessContext context) { return context.membershipId().value(); }

    private static FulfillmentOperationException invalid(String code) {
        return new FulfillmentOperationException(code, code != null && code.endsWith("_NOT_FOUND"));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
