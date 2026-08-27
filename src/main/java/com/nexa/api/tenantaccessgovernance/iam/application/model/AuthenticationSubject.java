package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.EmailAddress;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.util.Objects;

public record AuthenticationSubject(UserAccountId userAccountId, EmailAddress email, ClientSurface surface,
		AccessPolicy policy) {
	public AuthenticationSubject {
		Objects.requireNonNull(userAccountId, "User account id is required");
		Objects.requireNonNull(email, "Email address is required");
		Objects.requireNonNull(surface, "Client surface is required");
		Objects.requireNonNull(policy, "Access policy is required");
		if (policy.surface() != surface) throw new IllegalArgumentException("Policy surface does not match subject");
	}
}
