package com.nexa.api.iam.application.port.out;

import java.time.Instant;

/** Transactional hand-off. Implementations must not send network notifications inline. */
public interface SecurityNotificationOutboxPort {
    void enqueuePasswordReset(String recipient, String surface, String token, Instant expiresAt);
    void enqueuePasswordChanged(String recipient, String surface);
}
