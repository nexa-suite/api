package com.nexa.api.iam.domain.model.password;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTests {
	@Test
	void supportsPassphrasesWithinTheBoundedPolicy() {
		assertThat(PasswordPolicy.isValid("correct horse battery staple")).isTrue();
		assertThat(PasswordPolicy.isValid("short")).isFalse();
		assertThat(PasswordPolicy.isValid("x".repeat(129))).isFalse();
		assertThat(PasswordPolicy.isValid("valid\npassword long")).isFalse();
	}
}
