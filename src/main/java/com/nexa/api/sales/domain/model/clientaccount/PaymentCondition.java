package com.nexa.api.sales.domain.model.clientaccount;

public record PaymentCondition(String value) {
	public PaymentCondition { value = BusinessName.text(value, "Payment condition", 80); }
}
