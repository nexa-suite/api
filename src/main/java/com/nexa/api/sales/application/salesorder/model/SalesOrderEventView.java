package com.nexa.api.sales.application.salesorder.model;

import java.time.Instant;

public record SalesOrderEventView(String eventType, String fromStatus, String toStatus, String reason,
		String actorMembershipId, Instant occurredAt) { }
