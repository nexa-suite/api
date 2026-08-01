package com.nexa.api.sales.application.salesorder.model;

import java.math.BigDecimal;
import java.util.List;

public record FulfillmentCandidateView(String id, String number, String clientAccountId, String status,
		List<Line> lines) {
	public FulfillmentCandidateView { lines = List.copyOf(lines); }
	public record Line(String catalogItemId, String itemName, BigDecimal quantity, String unit) { }
}
