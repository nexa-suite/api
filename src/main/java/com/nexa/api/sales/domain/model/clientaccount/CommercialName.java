package com.nexa.api.sales.domain.model.clientaccount;

public record CommercialName(String value) {
	public CommercialName { value = BusinessName.text(value, "Commercial name", 160); }
}
