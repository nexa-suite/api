package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.LoginIdentifier;
import com.nexa.api.tenantaccessgovernance.iam.application.model.StoredUserAccount;

import java.util.Optional;

public interface UserAccountQueryPort {
	Optional<StoredUserAccount> findByLogin(LoginIdentifier login);
}
