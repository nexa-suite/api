package com.nexa.api.shared.application.port.out;

/** Verifies an existing password without coupling callers to a hashing algorithm. */
public interface PasswordVerificationPort {
	boolean matches(String rawPassword, String encodedPassword);
}
