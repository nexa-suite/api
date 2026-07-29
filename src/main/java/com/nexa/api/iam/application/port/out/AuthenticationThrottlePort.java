package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.LoginIdentifier;

import java.time.Instant;

public interface AuthenticationThrottlePort {
	boolean isThrottled(LoginIdentifier identifier, String clientFingerprint, Instant now);
	void recordFailure(LoginIdentifier identifier, String clientFingerprint, Instant now);
	void clear(LoginIdentifier identifier, String clientFingerprint);
}
