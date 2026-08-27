package com.nexa.api.tenantaccessgovernance.iam.application.model;

import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.session.AuthenticationSession;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;

import java.util.Objects;

public record ValidatedAccessSession(AuthenticationSession session, UserAccountId userId, ClientSurface surface) {
	public ValidatedAccessSession {
		Objects.requireNonNull(session, "Authentication session is required");
		Objects.requireNonNull(userId, "User id is required");
		Objects.requireNonNull(surface, "Surface is required");
		if (!session.userAccountId().equals(userId) || session.surface() != surface) {
			throw new IllegalArgumentException("Access session identity does not match token identity");
		}
	}
}
