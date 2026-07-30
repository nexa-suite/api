package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.math.BigDecimal;

public record RequestedQuantity(BigDecimal value) {
	public RequestedQuantity { if (value == null || value.signum() <= 0) throw new SalesInvariantViolation("Requested quantity must be greater than zero"); }
}
