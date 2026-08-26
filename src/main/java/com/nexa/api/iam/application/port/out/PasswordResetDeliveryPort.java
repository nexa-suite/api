package com.nexa.api.iam.application.port.out;

import java.time.Instant;

public interface PasswordResetDeliveryPort {
	void sendReset(String email, String surface, String token, Instant expiresAt);
	void sendPasswordChanged(String email, String surface);
	default void sendInvitation(String email, String displayName, String token, Instant expiresAt) { }

	/** Stable provider idempotency key for at-least-once delivery replay. */
	default void sendReset(String email, String surface, String token, Instant expiresAt, String deliveryKey) {
		sendReset(email, surface, token, expiresAt);
	}

	/** Stable provider idempotency key for at-least-once delivery replay. */
	default void sendPasswordChanged(String email, String surface, String deliveryKey) {
		sendPasswordChanged(email, surface);
	}

	/** Stable provider idempotency key for at-least-once delivery replay. */
	default void sendInvitation(String email, String displayName, String token, Instant expiresAt, String deliveryKey) {
		sendInvitation(email, displayName, token, expiresAt);
	}
}
