package com.nexa.api.salescommitment.presentation.salesorder.response;

import java.util.List;

public record SalesOrderPageResponse(List<SalesOrderResponse> items, int page, int size, long total) {
	public SalesOrderPageResponse { items = List.copyOf(items); }
}
