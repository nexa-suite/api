package com.nexa.api.iam.infrastructure.password;

import com.nexa.api.iam.application.port.out.PasswordVerificationPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Objects;

@Component
public final class BCryptPasswordVerifier implements PasswordVerificationPort {
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
}
