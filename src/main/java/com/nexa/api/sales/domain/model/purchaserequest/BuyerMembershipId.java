package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.util.UUID;

public record BuyerMembershipId(UUID value) {
	public BuyerMembershipId { if (value == null) throw new SalesInvariantViolation("Buyer membership id is required"); }
}
