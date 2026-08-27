package com.nexa.api.salescommitment.presentation.purchaserequest.response;

public record PurchaseRequestEventResponse(String eventId, String eventType, String fromStatus, String toStatus,
		String actorMembershipId, String occurredAt) { }
