package com.nexa.api.salescommitment.application.salesorder.model;

import java.time.Instant;

public record SalesOrderEventView(String id, String eventType, String fromStatus, String toStatus, String reason,
		String actorMembershipId, Instant occurredAt) { }
