package com.nexa.api.salescommitment.application.purchaserequest.model;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;

@NamedInterface("sales-public")
public record PurchaseRequestEventView(String id, String eventType, String fromStatus, String toStatus,
		String actorMembershipId, Instant occurredAt) { }
