package com.nexa.api.sales.presentation.salesorder.response;

import java.util.List;

public record FulfillmentCandidatePageResponse(List<FulfillmentCandidateResponse> items, int page, int size, long total) {
	public FulfillmentCandidatePageResponse { items = List.copyOf(items); }
}
