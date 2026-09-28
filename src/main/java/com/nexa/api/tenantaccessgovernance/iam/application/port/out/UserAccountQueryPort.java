package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.LoginIdentifier;
import com.nexa.api.tenantaccessgovernance.iam.application.model.StoredUserAccount;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccount;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.util.Optional;

public interface UserAccountQueryPort {
	Optional<StoredUserAccount> findByLogin(LoginIdentifier login);

	default Optional<UserAccount> findById(UserAccountId userAccountId) { return Optional.empty(); }
}
