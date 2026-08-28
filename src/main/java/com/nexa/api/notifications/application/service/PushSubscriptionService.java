package com.nexa.api.notifications.application.service;

import com.nexa.api.notifications.application.exception.NotificationOperationException;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for register, rotate, disable and unregister operations. */
@Service
@Profile("!test")
public class PushSubscriptionService {
    private final PushSubscriptionPersistencePort persistence;
    private final Clock clock;

    public PushSubscriptionService(PushSubscriptionPersistencePort persistence) {
        this(persistence, Clock.systemUTC());
    }

    @Autowired
    public PushSubscriptionService(PushSubscriptionPersistencePort persistence, Clock clock) {
        this.persistence = Objects.requireNonNull(persistence, "Push subscription persistence is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public PushSubscriptionPersistencePort.PushSubscription register(CurrentAccessContext context, String nativeClient,
                                                                       String installationId, String platform,
                                                                       String providerToken, String idempotencyKey) {
        context.requirePermission(PermissionKey.NOTIFICATION_READ);
        requireNative(nativeClient);
        requireKey(idempotencyKey);
        if (installationId == null || installationId.isBlank() || installationId.trim().length() > 160) throw invalid("PUSH_INSTALLATION_INVALID");
        String normalizedPlatform = platform == null ? "" : platform.trim().toUpperCase(Locale.ROOT);
        if (!normalizedPlatform.equals("IOS") && !normalizedPlatform.equals("ANDROID")) throw invalid("PUSH_PLATFORM_INVALID");
        if (providerToken == null || providerToken.isBlank() || providerToken.trim().length() > 4096) throw invalid("PUSH_TOKEN_INVALID");
        return persistence.register(new PushSubscriptionPersistencePort.RegisterRequest(
                context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(),
                context.userId().value(), context.surface().name(), installationId.trim(), normalizedPlatform,
                hash(providerToken.trim()), context.membershipId().value(), idempotencyKey.trim(),
                hash("push-register-v1|" + installationId.trim() + "|" + normalizedPlatform + "|" + hash(providerToken.trim())),
                clock.instant()));
    }

    @Transactional
    public PushSubscriptionPersistencePort.PushSubscription disable(CurrentAccessContext context, String nativeClient,
                                                                     UUID subscriptionId, String idempotencyKey, boolean unregister) {
        context.requirePermission(PermissionKey.NOTIFICATION_READ);
        requireNative(nativeClient);
        requireKey(idempotencyKey);
        if (subscriptionId == null) throw invalid("PUSH_SUBSCRIPTION_NOT_FOUND");
        return persistence.disable(new PushSubscriptionPersistencePort.DisableRequest(
                context.tenantId().value(), context.workspaceId().value(), context.membershipId().value(), subscriptionId,
                unregister ? "UNREGISTER" : "DISABLE", context.membershipId().value(), idempotencyKey.trim(),
                hash("push-" + (unregister ? "unregister" : "disable") + "-v1|" + subscriptionId), clock.instant()));
    }

    private static void requireNative(String value) {
        if (!"NATIVE".equalsIgnoreCase(value == null ? "" : value.trim())) throw invalid("PUSH_NATIVE_CLIENT_REQUIRED");
    }

    private static void requireKey(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 160) throw invalid("IDEMPOTENCY_KEY_REQUIRED");
    }

    private static NotificationOperationException invalid(String code) { return new NotificationOperationException(code, false); }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
