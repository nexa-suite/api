package com.nexa.api.salescommitment.presentation.salesorder.response;

import java.math.BigDecimal;
import java.util.List;

public record FulfillmentCandidateResponse(String id, String number, String clientAccountId, String status,
		List<Line> lines) {
	public FulfillmentCandidateResponse { lines = List.copyOf(lines); }
	public record Line(String catalogItemId, String itemName, BigDecimal quantity, String unit) { }
}
