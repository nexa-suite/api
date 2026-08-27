package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.jpa;

import com.nexa.api.tenantaccessgovernance.iam.application.model.LoginIdentifier;
import com.nexa.api.tenantaccessgovernance.iam.application.model.StoredUserAccount;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.UserAccountRepository;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.DisplayName;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccount;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountStatus;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.Username;
import org.springframework.stereotype.Repository;
import org.springframework.context.annotation.Profile;

import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class UserAccountJpaAdapter implements UserAccountRepository {
	private final UserAccountJpaRepository accounts;
	private final PasswordCredentialJpaRepository credentials;

	public UserAccountJpaAdapter(UserAccountJpaRepository accounts, PasswordCredentialJpaRepository credentials) {
		this.accounts = accounts;
		this.credentials = credentials;
	}

	@Override
	public Optional<StoredUserAccount> findByLogin(LoginIdentifier login) {
		return accounts.findByNormalizedEmailOrNormalizedUsername(login.value(), login.value())
				.flatMap(account -> credentials.findById(account.getId()).map(credential ->
					new StoredUserAccount(toDomain(account), credential.getPasswordHash())));
	}

	private static UserAccount toDomain(UserAccountJpaEntity entity) {
		UserAccount account = UserAccount.create(new UserAccountId(entity.getId().toString()),
				new Username(entity.getUsername()), new EmailAddress(entity.getEmail()), new DisplayName(entity.getDisplayName()));
		if (UserAccountStatus.SUSPENDED.name().equals(entity.getStatus())) account.suspend();
		if (UserAccountStatus.DISABLED.name().equals(entity.getStatus())) account.disable();
		return account;
	}
}
