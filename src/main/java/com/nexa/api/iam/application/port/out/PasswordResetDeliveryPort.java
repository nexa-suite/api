package com.nexa.api.iam.application.port.out;

import java.time.Instant;

public interface PasswordResetDeliveryPort {
	void sendReset(String email, String surface, String token, Instant expiresAt);
	void sendPasswordChanged(String email, String surface);
}
