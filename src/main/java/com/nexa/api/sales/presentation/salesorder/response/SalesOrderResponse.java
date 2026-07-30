package com.nexa.api.sales.presentation.salesorder.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SalesOrderResponse(String id, String number, String tenantId, String workspaceId, String clientAccountId,
		String buyerMembershipId, String sourcePurchaseRequestId, String currency, BigDecimal total, String status,
		Instant createdAt, Instant confirmedAt, String rejectionReason, long version, List<SalesOrderLineResponse> lines) {
	public SalesOrderResponse { lines = List.copyOf(lines); }
}
