package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

/** Atomic, privacy-safe reset throttle. Returns the highest matching bucket count. */
public interface PasswordResetThrottlePort {
    long recordAttempt(String normalizedIdentifier, String clientAddress);
}
