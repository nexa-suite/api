package com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAccountTests {
	@Test
	void createsAnActiveAccountWithNormalizedValueObjects() {
		UserAccount account = UserAccount.create(new UserAccountId("user-1"), new Username("Carlos.Rios"),
				new EmailAddress(" CARLOS@ICISA.PE "), new DisplayName(" Carlos Rios "));

		assertThat(account.status()).isEqualTo(UserAccountStatus.ACTIVE);
		assertThat(account.canAuthenticate()).isTrue();
		assertThat(account.username().value()).isEqualTo("carlos.rios");
		assertThat(account.email().value()).isEqualTo("carlos@icisa.pe");
		assertThat(account.displayName().value()).isEqualTo("Carlos Rios");
	}

	@Test
	void statusTransitionsControlAuthentication() {
		UserAccount account = account();

		account.suspend();
		assertThat(account.status()).isEqualTo(UserAccountStatus.SUSPENDED);
		assertThat(account.canAuthenticate()).isFalse();
		account.disable();
		assertThat(account.status()).isEqualTo(UserAccountStatus.DISABLED);
		assertThat(account.canAuthenticate()).isFalse();
		account.activate();
		assertThat(account.status()).isEqualTo(UserAccountStatus.ACTIVE);
		assertThat(account.canAuthenticate()).isTrue();
	}

	@Test
	void rejectsInvalidValuesAndRequiredState() {
		assertThatThrownBy(() -> UserAccount.create(null, new Username("user"), new EmailAddress("user@example.com"), name()))
				.isInstanceOf(UserAccountInvariantViolation.class);
		assertThatThrownBy(() -> new EmailAddress("not-an-email")).isInstanceOf(UserAccountInvariantViolation.class);
		assertThatThrownBy(() -> new Username("not valid")).isInstanceOf(UserAccountInvariantViolation.class);
		assertThatThrownBy(() -> new DisplayName(" ")).isInstanceOf(UserAccountInvariantViolation.class);
	}

	@Test
	void accountDoesNotOwnCredentialsOrAccessContext() {
		assertThat(Arrays.stream(UserAccount.class.getDeclaredFields()).map(Field::getName))
				.doesNotContain("password", "passwordHash", "role", "permissions", "tenant", "tenantId", "workspace", "workspaceId");
	}

	private static UserAccount account() {
		return UserAccount.create(new UserAccountId("user-1"), new Username("carlos"),
				new EmailAddress("carlos@example.com"), name());
	}

	private static DisplayName name() { return new DisplayName("Carlos Rios"); }
}
