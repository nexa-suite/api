package com.nexa.api.sales.presentation.salesorder.response;

import java.time.Instant;

public record SalesOrderEventResponse(String eventType, String fromStatus, String toStatus, String reason,
		String actorMembershipId, Instant occurredAt) { }
