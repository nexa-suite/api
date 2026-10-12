package com.nexa.api.salescommitment.application.salesorder.model;

import org.springframework.modulith.NamedInterface;
import java.math.BigDecimal;
import java.util.List;

@NamedInterface("sales-public")
public record FulfillmentCandidateView(String id, String number, String clientAccountId, String status,
		long version, List<Line> lines) {
	public FulfillmentCandidateView { lines = List.copyOf(lines); }
	public record Line(String catalogItemId, String itemName, BigDecimal quantity, String unit) { }
}
