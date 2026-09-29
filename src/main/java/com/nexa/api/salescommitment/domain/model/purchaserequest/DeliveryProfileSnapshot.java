package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.publicapi.SalesInvariantViolation;

public record DeliveryProfileSnapshot(String value) {
	public DeliveryProfileSnapshot { if (value != null && value.length() > 2000) throw new SalesInvariantViolation("Delivery profile snapshot is too long"); value = value == null ? null : value.trim(); }
}
