package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Assignee;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Command;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.ExceptionView;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.BomOperationalExceptionModels.Snapshot;
import com.nexa.api.fulfillmentdelivery.application.port.BomOperationalExceptionPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Coordinates case ownership and follow-up while preserving underlying Delivery authority. */
@Service
@Profile("!test")
public class BomOperationalExceptionService {
    private final BomOperationalExceptionPersistencePort persistence;
    private final WorkforceDirectory workforce;
    private final Clock clock;

    public BomOperationalExceptionService(BomOperationalExceptionPersistencePort persistence,
            WorkforceDirectory workforce, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence);
        this.workforce = Objects.requireNonNull(workforce);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public Snapshot list(CurrentAccessContext context) {
        requireRead(context);
        return persistence.list(tenant(context), workspace(context));
    }

    @Transactional(readOnly = true)
    public List<Assignee> assignees(CurrentAccessContext context, UUID exceptionId) {
        requireRead(context);
        if (exceptionId == null) throw error("OPERATIONAL_EXCEPTION_NOT_FOUND", true);
        return persistence.assignees(tenant(context), workspace(context), exceptionId,
                workforce.findExceptionAssignees(tenant(context), workspace(context)));
    }

    @Transactional
    public MutationResult mutate(CurrentAccessContext context, UUID exceptionId, String operation,
            UUID targetMembershipId, long expectedVersion, String idempotencyKey, String reason, String note) {
        requireCoordinate(context);
        if (exceptionId == null || expectedVersion < 0 || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 160) throw error("INVALID_REQUEST", false);
        String normalizedOperation = switch (operation == null ? "" : operation) {
            case "CLAIM", "ASSIGN", "FOLLOW_UP", "RESOLVE", "CLOSE" -> operation;
            default -> throw error("INVALID_REQUEST", false);
        };
        String normalizedReason = normalized(reason, 500, false);
        String normalizedNote = note == null ? null : normalized(note, 2000, true);
        if ("ASSIGN".equals(normalizedOperation)) {
            if (targetMembershipId == null) throw error("INVALID_REQUEST", false);
            WorkforceDirectory.ExceptionAssignee target = workforce.findExceptionAssignee(tenant(context),
                    workspace(context), targetMembershipId).orElseThrow(() -> error("EXCEPTION_ASSIGNEE_NOT_ELIGIBLE", false));
            boolean driverStillAssigned = target.driverReporter()
                    && persistence.isCurrentDriverAssignment(tenant(context), workspace(context), exceptionId,
                    targetMembershipId);
            if (!target.coordinator() && !driverStillAssigned) throw error("EXCEPTION_ASSIGNEE_NOT_ELIGIBLE", false);
        }
        String payload = "bom-operational-exception-v1|" + normalizedOperation + "|" + exceptionId + "|"
                + targetMembershipId + "|" + expectedVersion + "|" + Objects.toString(normalizedReason, "")
                + "|" + Objects.toString(normalizedNote, "");
        Command command = new Command(tenant(context), workspace(context), actor(context), exceptionId,
                targetMembershipId, expectedVersion, normalizedOperation, normalizedReason, normalizedNote,
                idempotencyKey, hash(payload), clock.instant());
        return persistence.mutate(command);
    }

    private static String normalized(String value, int maxLength, boolean optional) {
        String result = value == null ? null : value.trim();
        if (result == null || result.isBlank()) {
            if (optional) return null;
            throw error("INVALID_REQUEST", false);
        }
        if (result.length() > maxLength) throw error("INVALID_REQUEST", false);
        return result;
    }

    private static void requireRead(CurrentAccessContext context) {
        context.requirePermission(PermissionKey.DELIVERY_EXCEPTION_READ);
    }

    private static void requireCoordinate(CurrentAccessContext context) {
        context.requirePermission(PermissionKey.DELIVERY_EXCEPTION_COORDINATE);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }
    private static UUID actor(CurrentAccessContext context) { return context.membershipId().value(); }

    private static FulfillmentOperationException error(String code, boolean notFound) {
        return new FulfillmentOperationException(code, notFound);
    }
}
