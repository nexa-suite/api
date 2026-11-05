package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.PasswordResetDeliveryChannel;

import java.time.Instant;

public interface PasswordResetDeliveryPort {
	void sendReset(String email, String surface, String token, Instant expiresAt);
	void sendPasswordChanged(String email, String surface);
	default void sendInvitation(String email, String displayName, String token, Instant expiresAt) { }

	/** Stable provider idempotency key for at-least-once delivery replay. */
	default void sendReset(String email, String surface, String token, Instant expiresAt, String deliveryKey) {
		sendReset(email, surface, token, expiresAt);
	}

	/** Routes to the configured reset URL for this client channel. */
	default void sendReset(String email, String surface, PasswordResetDeliveryChannel channel,
			String token, Instant expiresAt, String deliveryKey) {
		if (channel != PasswordResetDeliveryChannel.WEB) {
			throw new UnsupportedOperationException("Native password-reset delivery is not configured");
		}
		sendReset(email, surface, token, expiresAt, deliveryKey);
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
