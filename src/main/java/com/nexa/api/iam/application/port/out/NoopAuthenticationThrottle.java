package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.LoginIdentifier;

import java.time.Instant;

public final class NoopAuthenticationThrottle implements AuthenticationThrottlePort {
	@Override public boolean isThrottled(LoginIdentifier identifier, String clientFingerprint, Instant now) { return false; }
	@Override public void recordFailure(LoginIdentifier identifier, String clientFingerprint, Instant now) { }
	@Override public boolean recordFailureAndCheck(LoginIdentifier identifier, String clientFingerprint, Instant now) { return false; }
	@Override public void clear(LoginIdentifier identifier, String clientFingerprint) { }
}
