package com.nexa.api.sales.presentation.clientaccount.response;

import java.util.List;

public record ClientAccountPageResponse(List<ClientAccountSummaryResponse> items, int page, int size, long total) {
	public ClientAccountPageResponse { items = List.copyOf(items); }
}
