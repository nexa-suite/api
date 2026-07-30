package com.nexa.api.sales.presentation.purchaserequest.response;

public record PurchaseRequestEventResponse(String eventType, String fromStatus, String toStatus, String occurredAt) { }
