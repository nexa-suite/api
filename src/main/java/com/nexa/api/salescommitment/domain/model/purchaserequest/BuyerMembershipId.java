package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.util.UUID;

public record BuyerMembershipId(UUID value) {
	public BuyerMembershipId { if (value == null) throw new SalesInvariantViolation("Buyer membership id is required"); }
}
