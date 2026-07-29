package com.nexa.api.iam.infrastructure.password;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class BCryptPasswordVerifierTests {
	@Test
	void verifiesBcryptAndRejectsMalformedHashes() {
		String hash = new BCryptPasswordEncoder().encode("Password123!");
		BCryptPasswordVerifier verifier = new BCryptPasswordVerifier();

		assertThat(verifier.matches("Password123!", hash)).isTrue();
		assertThat(verifier.matches("wrong", hash)).isFalse();
		assertThat(verifier.matches("Password123!", "not-a-bcrypt-hash")).isFalse();
	}
}
