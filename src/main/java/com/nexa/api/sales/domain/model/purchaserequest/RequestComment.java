package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record RequestComment(String value) {
	public RequestComment { if (value != null && value.length() > 2000) throw new SalesInvariantViolation("Request comment is too long"); value = value == null ? null : value.trim(); }
}
