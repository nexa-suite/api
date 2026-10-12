package com.nexa.api.notifications.application.publicapi;

import java.time.Instant;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Immutable facts accepted by the notification projection workflow. */
public final class NotificationProjectionModels {
    private NotificationProjectionModels() { }
	public record NotificationProjection(String eventId, String tenantId, String workspaceId,
			String clientAccountId, String aggregateType, String aggregateId, String eventType,
			String publicStatus, Instant occurredAt, Set<String> recipientMembershipIds,
			String sourcePayloadSha256) {
		public NotificationProjection(String eventId, String tenantId, String workspaceId,
				String clientAccountId, String aggregateType, String aggregateId, String eventType,
				String publicStatus, Instant occurredAt, Set<String> recipientMembershipIds) {
			this(eventId, tenantId, workspaceId, clientAccountId, aggregateType, aggregateId, eventType,
					publicStatus, occurredAt, recipientMembershipIds, null);
		}

		public NotificationProjection {
			recipientMembershipIds = Set.copyOf(recipientMembershipIds);
		}
	}

	/** Hashes the exact PostgreSQL JSONB text selected by Tenant outbox consumers. */
	public static String sourcePayloadSha256(String payloadText) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(payloadText.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	public record PushNotificationCandidate(NotificationProjection projection, String category, String title,
			String message, String deepLink) { }

}
