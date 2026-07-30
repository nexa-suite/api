package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.util.UUID;

public record PurchaseRequestLineId(UUID value) {
	public PurchaseRequestLineId { if (value == null) throw new SalesInvariantViolation("Purchase request line id is required"); }
}
