package com.nexa.api.sales.presentation.purchaserequest.response;

public record PurchaseRequestEventResponse(String eventId, String eventType, String fromStatus, String toStatus,
		String actorMembershipId, String occurredAt) { }
