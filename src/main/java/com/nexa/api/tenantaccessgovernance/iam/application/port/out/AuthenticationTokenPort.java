package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AuthenticationSubject;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.tenantaccessgovernance.iam.domain.publicapi.SessionId;

import java.time.Instant;

public interface AuthenticationTokenPort {
	IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt);

	default IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt, SessionId sessionId) {
		return issue(subject, issuedAt);
	}
}
