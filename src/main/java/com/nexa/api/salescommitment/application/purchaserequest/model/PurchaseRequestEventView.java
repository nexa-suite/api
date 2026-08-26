package com.nexa.api.salescommitment.application.purchaserequest.model;

import java.time.Instant;

public record PurchaseRequestEventView(String id, String eventType, String fromStatus, String toStatus,
		String actorMembershipId, Instant occurredAt) { }
