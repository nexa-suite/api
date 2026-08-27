package com.nexa.api.salescommitment.domain.model.purchaserequest;

import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;

import java.util.Locale;

public enum PurchaseRequestPriority {
	NORMAL, HIGH, URGENT;

	public static PurchaseRequestPriority from(String value) {
		if (value == null || value.isBlank()) return NORMAL;
		try {
			return value.trim().toUpperCase(Locale.ROOT).transform(PurchaseRequestPriority::valueOf);
		} catch (IllegalArgumentException exception) {
			throw new SalesInvariantViolation("Purchase request priority is invalid");
		}
	}
}
