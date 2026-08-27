package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

/** Durable anti-abuse counter for the public contact boundary. */
public interface PublicContactThrottlePort {
    long recordAttempt(String normalizedEmail, String clientAddress);
}
