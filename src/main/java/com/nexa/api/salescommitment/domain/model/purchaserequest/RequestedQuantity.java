package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.math.BigDecimal;

public record RequestedQuantity(BigDecimal value) {
	public RequestedQuantity { if (value == null || value.signum() <= 0) throw new SalesInvariantViolation("Requested quantity must be greater than zero"); }
}
