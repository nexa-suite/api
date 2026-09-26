package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

/** Verifies an existing password without coupling callers to a hashing algorithm. */
public interface PasswordVerificationPortContract {
	boolean matches(String rawPassword, String encodedPassword);
}
