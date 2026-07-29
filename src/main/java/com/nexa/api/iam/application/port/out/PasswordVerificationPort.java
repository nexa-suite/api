package com.nexa.api.iam.application.port.out;

public interface PasswordVerificationPort {
	boolean matches(String rawPassword, String encodedPassword);
}
