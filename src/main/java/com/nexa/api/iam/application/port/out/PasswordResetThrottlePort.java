package com.nexa.api.iam.application.port.out;

/** Atomic, privacy-safe reset throttle. Returns the highest matching bucket count. */
public interface PasswordResetThrottlePort {
    long recordAttempt(String normalizedIdentifier, String clientAddress);
}
