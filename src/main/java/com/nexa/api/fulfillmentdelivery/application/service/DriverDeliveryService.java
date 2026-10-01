package com.nexa.api.fulfillmentdelivery.application.service;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptStartRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.AttemptStartResult;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.ArrivalRequest;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.ArrivalView;
import com.nexa.api.fulfillmentdelivery.application.model.DriverDeliveryModels.DeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.port.DriverDeliveryPersistencePort;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for assigned-driver delivery execution. */
@Service
@Profile("!test")
public class DriverDeliveryService {
    private final DriverDeliveryPersistencePort persistence;
    private final FulfillmentLifecycleService lifecycle;
    private final Clock clock;

    public DriverDeliveryService(DriverDeliveryPersistencePort persistence, FulfillmentLifecycleService lifecycle,
                                 Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "Driver delivery persistence is required");
        this.lifecycle = Objects.requireNonNull(lifecycle, "Fulfillment lifecycle is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public List<DeliveryView> listAssigned(CurrentAccessContext context) {
        context.requirePermission(PermissionKey.DISPATCH_READ);
        return persistence.listAssigned(tenant(context), workspace(context), actor(context));
    }

    @Transactional(readOnly = true)
    public DeliveryView getAssigned(CurrentAccessContext context, UUID deliveryId) {
        context.requirePermission(PermissionKey.DISPATCH_READ);
        return persistence.findAssigned(tenant(context), workspace(context), actor(context), deliveryId);
    }

    @Transactional
    public AttemptStartResult startAttempt(CurrentAccessContext context, UUID deliveryId,
                                           long expectedVersion, String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new FulfillmentOperationException("IDEMPOTENCY_KEY_REQUIRED", false);
        }
        if (expectedVersion < 0) throw new FulfillmentOperationException("VERSION_INVALID", false);
        return persistence.startAttempt(new AttemptStartRequest(tenant(context), workspace(context), deliveryId,
                actor(context), expectedVersion, idempotencyKey,
                hash("driver-attempt-start-v1|" + deliveryId + "|" + expectedVersion), clock.instant()));
    }

    @Transactional
    public FulfillmentModels.DeliveryOutcomeResult recordOutcome(
            CurrentAccessContext context, UUID deliveryId, UUID attemptId, long expectedVersion,
            String idempotencyKey, FulfillmentLifecycleService.AttemptCommand command) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        if (deliveryId == null || attemptId == null) {
            throw new FulfillmentOperationException("DELIVERY_ATTEMPT_NOT_FOUND", true);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new FulfillmentOperationException("IDEMPOTENCY_KEY_REQUIRED", false);
        }
        if (expectedVersion < 0) throw new FulfillmentOperationException("VERSION_INVALID", false);
        persistence.requireAssignedAttempt(
                tenant(context), workspace(context), actor(context), deliveryId, attemptId, idempotencyKey);
        return lifecycle.recordDriverAttempt(context, deliveryId, expectedVersion, idempotencyKey, command);
    }

    @Transactional
    public ArrivalView signalArrival(CurrentAccessContext context, UUID deliveryId, UUID attemptId,
                                    long expectedVersion, String idempotencyKey) {
        context.requirePermission(PermissionKey.DISPATCH_START_ROUTE);
        if (deliveryId == null || attemptId == null) {
            throw new FulfillmentOperationException("DELIVERY_ATTEMPT_NOT_FOUND", true);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new FulfillmentOperationException("IDEMPOTENCY_KEY_REQUIRED", false);
        }
        if (expectedVersion < 0) throw new FulfillmentOperationException("VERSION_INVALID", false);
        ArrivalView result = persistence.signalArrival(new ArrivalRequest(
                tenant(context), workspace(context), deliveryId, attemptId, actor(context), expectedVersion,
                idempotencyKey,
                hash("driver-arrival-v1|" + deliveryId + "|" + attemptId + "|" + expectedVersion),
                clock.instant()));
        if (!result.replayed()) lifecycle.traceDriverArrival(context, result, idempotencyKey);
        return result;
    }

    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }
    private static UUID actor(CurrentAccessContext context) { return context.membershipId().value(); }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
