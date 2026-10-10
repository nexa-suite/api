package com.nexa.api.salescommitment.application.salesorder.model;

import org.springframework.modulith.NamedInterface;
import java.time.Instant;

@NamedInterface("sales-public")
public record SalesOrderEventView(String id, String eventType, String fromStatus, String toStatus, String reason,
		String actorMembershipId, Instant occurredAt) { }
