package com.nexa.api.sales.presentation.purchaserequest.response;

import java.util.List;

public record PurchaseRequestPageResponse(List<PurchaseRequestSummaryResponse> items, int page, int size, long total) {
	public PurchaseRequestPageResponse { items = List.copyOf(items); }
}
