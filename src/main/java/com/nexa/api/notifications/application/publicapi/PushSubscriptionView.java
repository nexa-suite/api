package com.nexa.api.notifications.application.publicapi;

import java.time.Instant;
import java.util.UUID;

/** Public application view for a registered native push subscription. */
public record PushSubscriptionView(UUID id, UUID recipientMembershipId, String installationId,
        String platform, String surface, String status, Instant createdAt, Instant updatedAt, long version) { }
