package com.nexa.api.tenantaccessgovernance.iam.infrastructure.password;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordVerificationPort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordHashPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Objects;

@Component
public final class BCryptPasswordVerifier implements PasswordVerificationPort, PasswordHashPort,
		com.nexa.api.shared.application.port.out.PasswordVerificationPort {
	private final BCryptPasswordEncoder encoder;

	public BCryptPasswordVerifier() {
		this(new BCryptPasswordEncoder(12));
	}

	@Autowired
	public BCryptPasswordVerifier(@Value("${nexa.security.bcrypt-strength:12}") int strength) {
		this(new BCryptPasswordEncoder(strength));
	}

	public BCryptPasswordVerifier(BCryptPasswordEncoder encoder) {
		this.encoder = Objects.requireNonNull(encoder, "BCrypt encoder is required");
	}

	@Override
	public boolean matches(String rawPassword, String encodedPassword) {
		if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) return false;
		try {
			return encoder.matches(rawPassword, encodedPassword);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@Override
	public String encode(String rawPassword) {
		if (rawPassword == null || rawPassword.isBlank()) throw new IllegalArgumentException("Password is required");
		return encoder.encode(rawPassword);
	}
}
