package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccount;

import java.util.Objects;

/** Persistence projection for authentication; the hash never becomes UserAccount state. */
public record StoredUserAccount(UserAccount account, String passwordHash) {
	public StoredUserAccount {
		Objects.requireNonNull(account, "User account is required");
		if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("Password hash is required");
	}
}
