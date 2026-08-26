package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.util.UUID;

public record PurchaseRequestLineId(UUID value) {
	public PurchaseRequestLineId { if (value == null) throw new SalesInvariantViolation("Purchase request line id is required"); }
}
