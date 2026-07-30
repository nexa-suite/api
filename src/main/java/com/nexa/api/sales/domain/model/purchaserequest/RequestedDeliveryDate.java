package com.nexa.api.sales.domain.model.purchaserequest;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.time.LocalDate;

public record RequestedDeliveryDate(LocalDate value) {
	public RequestedDeliveryDate { if (value != null && value.isBefore(LocalDate.now())) throw new SalesInvariantViolation("Requested delivery date cannot be in the past"); }
}
