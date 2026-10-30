package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.PasswordResetDeliveryChannel;

import java.time.Instant;

/** Transactional hand-off. Implementations must not send network notifications inline. */
public interface SecurityNotificationOutboxPortContract {
    void enqueuePasswordReset(String recipient, String surface, String token, Instant expiresAt);

    default void enqueuePasswordReset(String recipient, String surface, PasswordResetDeliveryChannel channel,
            String token, Instant expiresAt) {
        if (channel != PasswordResetDeliveryChannel.WEB) {
            throw new UnsupportedOperationException("Native password-reset outbox routing is not configured");
        }
        enqueuePasswordReset(recipient, surface, token, expiresAt);
    }

    void enqueuePasswordChanged(String recipient, String surface);

    default void enqueueInvitation(String recipient, String displayName, String token, Instant expiresAt) {
        throw new UnsupportedOperationException("Invitation delivery is not configured");
    }
}
