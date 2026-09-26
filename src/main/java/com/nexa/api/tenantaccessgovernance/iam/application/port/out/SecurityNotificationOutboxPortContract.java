package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import java.time.Instant;

/** Transactional hand-off. Implementations must not send network notifications inline. */
public interface SecurityNotificationOutboxPortContract {
    void enqueuePasswordReset(String recipient, String surface, String token, Instant expiresAt);
    void enqueuePasswordChanged(String recipient, String surface);

    default void enqueueInvitation(String recipient, String displayName, String token, Instant expiresAt) {
        throw new UnsupportedOperationException("Invitation delivery is not configured");
    }
}
