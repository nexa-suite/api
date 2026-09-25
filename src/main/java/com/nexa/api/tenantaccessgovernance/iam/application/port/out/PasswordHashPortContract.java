package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

/** Hashes credentials at the infrastructure boundary without exposing a concrete algorithm. */
public interface PasswordHashPortContract {
    String encode(String rawPassword);
}
