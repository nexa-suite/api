package com.nexa.api.iam.application.port.out;

/** Durable anti-abuse counter for the public contact boundary. */
public interface PublicContactThrottlePort {
    long recordAttempt(String normalizedEmail, String clientAddress);
}
