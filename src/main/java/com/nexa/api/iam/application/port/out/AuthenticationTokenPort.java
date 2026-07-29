package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.AuthenticationSubject;
import com.nexa.api.iam.application.model.IssuedAuthenticationTokens;

import java.time.Instant;

public interface AuthenticationTokenPort {
	IssuedAuthenticationTokens issue(AuthenticationSubject subject, Instant issuedAt);
}
