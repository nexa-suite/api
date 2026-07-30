package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;
import com.nexa.api.iam.domain.model.session.SessionId;

import java.time.Instant;

public interface AuthenticationTokenPort {
	IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt);

	default IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt, SessionId sessionId) {
		return issue(subject, issuedAt);
	}
}
