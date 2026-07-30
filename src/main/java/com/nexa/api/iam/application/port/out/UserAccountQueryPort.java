package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.LoginIdentifier;
import com.nexa.api.iam.application.model.StoredUserAccount;

import java.util.Optional;

public interface UserAccountQueryPort {
	Optional<StoredUserAccount> findByLogin(LoginIdentifier login);
}
