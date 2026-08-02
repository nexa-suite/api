package com.nexa.api.iam.application.port.out;

/** Locking persistence boundary for the PasswordResetRequest workflow. */
public interface PasswordResetPersistencePort {
    String request(String email, String surface, String clientAddress, String correlationId, String traceId);
    void complete(String token, String newPassword, String correlationId, String traceId);
}
