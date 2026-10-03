package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.EvidenceRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryIncidentModels.IncidentView;
import com.nexa.api.fulfillmentdelivery.application.port.DriverDeliveryIncidentPersistencePort;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.DriverDeliveryIncidentType;
import com.nexa.api.fulfillmentdelivery.domain.operationalexception.OperationalExceptionSourceClassifier;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Current-driver append-only incident commands; delivery outcomes are never modified. */
@Service
@Profile("!test")
public class DriverDeliveryIncidentService {
    private final DriverDeliveryIncidentPersistencePort persistence;
    private final BusinessEvidenceQuery businessEvidence;
    private final BusinessTraceabilityCommands traceability;
    private final Clock clock;

    public DriverDeliveryIncidentService(DriverDeliveryIncidentPersistencePort persistence,
                                         BusinessEvidenceQuery businessEvidence,
                                         BusinessTraceabilityCommands traceability, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence);
        this.businessEvidence = Objects.requireNonNull(businessEvidence);
        this.traceability = Objects.requireNonNull(traceability);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public IncidentView record(CurrentAccessContext context, UUID deliveryId, UUID attemptId,
                               long expectedVersion, String key, String reason,
                               String description, String place, DriverDeliveryIncidentType type) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        requireCommand(deliveryId, attemptId, expectedVersion, key);
        String cleanReason = requiredText(reason, 500, "INCIDENT_REASON_REQUIRED");
        String cleanDescription = requiredText(description, 2000, "INCIDENT_DESCRIPTION_REQUIRED");
        String cleanPlace = requiredText(place, 500, "INCIDENT_PLACE_REQUIRED");
        var severity = type == null ? null : OperationalExceptionSourceClassifier.classify(type);
        String requestHash = type == null
                ? fingerprint("driver-delivery-incident-v1", tenant(context), workspace(context), actor(context),
                        deliveryId, attemptId, expectedVersion, cleanReason, cleanDescription, cleanPlace)
                : fingerprint("driver-delivery-incident-v2", tenant(context), workspace(context), actor(context),
                        deliveryId, attemptId, expectedVersion, type, cleanReason, cleanDescription, cleanPlace);
        IncidentRequest request = new IncidentRequest(tenant(context), workspace(context), deliveryId,
                attemptId, actor(context), expectedVersion, key, requestHash,
                type, severity, cleanReason, cleanDescription, cleanPlace, clock.instant());
        IncidentView result = persistence.recordIncident(request);
        if (!result.replayed()) {
            traceability.record(new BusinessTraceabilityCommands.TraceRequest(tenant(context), workspace(context),
                    actor(context), "DRIVER", "DRIVER_DELIVERY_INCIDENT_RECORDED", "DeliveryIncident",
                    result.id(), deliveryId.toString(), "driver-delivery-incident:" + key,
                    Map.of("deliveryId", deliveryId.toString(), "attemptId", attemptId.toString(),
                            "type", result.type().name(), "severity", result.severity().name()),
                    result.recordedAt()));
        }
        return result;
    }

    @Transactional
    public IncidentView attachEvidence(CurrentAccessContext context, UUID deliveryId, UUID attemptId,
                                       UUID incidentId, long expectedVersion, String key,
                                       List<UUID> requestedEvidenceIds) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        context.requirePermission(PermissionKey.DOCUMENT_UPLOAD);
        requireCommand(deliveryId, attemptId, expectedVersion, key);
        if (incidentId == null) throw error("DELIVERY_INCIDENT_NOT_FOUND", true);
        List<UUID> evidenceIds = normalizeEvidenceIds(requestedEvidenceIds);
        if (evidenceIds.isEmpty()) throw error("INCIDENT_EVIDENCE_REQUIRED", false);
        String requestHash = fingerprint("driver-delivery-incident-evidence-v1", tenant(context), workspace(context),
                actor(context), deliveryId, attemptId, incidentId, expectedVersion,
                String.join(",", evidenceIds.stream().map(UUID::toString).toList()));
        EvidenceRequest request = new EvidenceRequest(tenant(context), workspace(context), deliveryId, attemptId,
                incidentId, actor(context), expectedVersion, key, requestHash, evidenceIds, clock.instant());

        IncidentView replay = persistence.findEvidenceReplay(request);
        if (replay != null) return replay;
        for (UUID evidenceId : evidenceIds) {
            if (!businessEvidence.isAvailableForSubject(tenant(context), workspace(context), evidenceId,
                    "DELIVERY_INCIDENT", incidentId)) {
                throw error("BUSINESS_EVIDENCE_NOT_AVAILABLE", false);
            }
        }
        IncidentView result = persistence.appendEvidence(request);
        if (!result.replayed()) {
            traceability.record(new BusinessTraceabilityCommands.TraceRequest(tenant(context), workspace(context),
                    actor(context), "DRIVER", "DRIVER_DELIVERY_INCIDENT_EVIDENCE_ATTACHED", "DeliveryIncident",
                    result.id(), deliveryId.toString(), "driver-delivery-incident-evidence:" + key,
                    Map.of("deliveryId", deliveryId.toString(), "attemptId", attemptId.toString(),
                            "evidenceObjectIds", evidenceIds.stream().map(UUID::toString).toList()), clock.instant()));
        }
        return result;
    }

    private static List<UUID> normalizeEvidenceIds(List<UUID> values) {
        if (values == null) return List.of();
        if (values.size() > 16 || values.stream().anyMatch(Objects::isNull)) {
            throw error("INCIDENT_EVIDENCE_INVALID", false);
        }
        return values.stream().distinct().sorted().toList();
    }

    private static String requiredText(String value, int limit, String errorCode) {
        if (value == null || value.isBlank() || value.trim().length() > limit) throw error(errorCode, false);
        return value.trim();
    }

    private static void requireCommand(UUID deliveryId, UUID attemptId, long expectedVersion, String key) {
        if (deliveryId == null || attemptId == null) throw error("DELIVERY_ATTEMPT_NOT_FOUND", true);
        if (expectedVersion < 0) throw error("PRECONDITION_INVALID", false);
        if (key == null || key.isBlank() || key.length() > 160) throw error("IDEMPOTENCY_KEY_REQUIRED", false);
    }

    private static String fingerprint(String prefix, Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, prefix);
            for (Object part : parts) add(digest, part.toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }

    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }
    private static UUID actor(CurrentAccessContext context) { return context.membershipId().value(); }
}
