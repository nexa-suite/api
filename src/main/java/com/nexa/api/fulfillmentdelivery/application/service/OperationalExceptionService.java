package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ClaimRequest;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ExceptionSetView;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.MutationResult;
import com.nexa.api.fulfillmentdelivery.application.model.OperationalExceptionModels.ReviewRequest;
import com.nexa.api.fulfillmentdelivery.application.port.OperationalExceptionPersistencePort;
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
import java.util.Objects;
import java.util.UUID;

/** Current-assignment authority for the bounded Driver exception lifecycle. */
@Service
@Profile("!test")
public class OperationalExceptionService {
    private final OperationalExceptionPersistencePort persistence;
    private final Clock clock;

    public OperationalExceptionService(OperationalExceptionPersistencePort persistence, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "Operational-exception persistence is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public ExceptionSetView getForDriver(CurrentAccessContext context, UUID deliveryId) {
        context.requirePermission(PermissionKey.DISPATCH_READ);
        if (deliveryId == null) throw error("DELIVERY_NOT_FOUND", true);
        return persistence.findForDriver(tenant(context), workspace(context), actor(context), deliveryId);
    }

    @Transactional
    public MutationResult claim(CurrentAccessContext context, UUID deliveryId, UUID exceptionId,
                                long expectedDeliveryVersion, String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        validateCommand(deliveryId, exceptionId, expectedDeliveryVersion, idempotencyKey);
        return persistence.claim(new ClaimRequest(tenant(context), workspace(context), deliveryId, exceptionId,
                actor(context), expectedDeliveryVersion, idempotencyKey,
                hash("operational-exception-claim-v1|" + deliveryId + "|" + exceptionId + "|"
                        + expectedDeliveryVersion), clock.instant()));
    }

    @Transactional
    public MutationResult review(CurrentAccessContext context, UUID deliveryId, UUID exceptionId,
                                 long expectedDeliveryVersion, String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        validateCommand(deliveryId, exceptionId, expectedDeliveryVersion, idempotencyKey);
        return persistence.review(new ReviewRequest(tenant(context), workspace(context), deliveryId, exceptionId,
                actor(context), expectedDeliveryVersion, idempotencyKey,
                hash("operational-exception-review-v1|" + deliveryId + "|" + exceptionId + "|"
                        + expectedDeliveryVersion), clock.instant()));
    }

    private static void validateCommand(UUID deliveryId, UUID exceptionId, long expectedVersion, String key) {
        if (deliveryId == null || exceptionId == null) throw error("DELIVERY_NOT_FOUND", true);
        if (expectedVersion < 0) throw error("VERSION_INVALID", false);
        if (key == null || key.isBlank() || key.length() > 160) throw error("IDEMPOTENCY_KEY_REQUIRED", false);
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
