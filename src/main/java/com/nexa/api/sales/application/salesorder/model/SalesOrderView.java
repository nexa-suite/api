package com.nexa.api.sales.application.salesorder.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SalesOrderView(String id, String number, String tenantId, String workspaceId, String clientAccountId,
		String buyerMembershipId, String sourcePurchaseRequestId, String currency, BigDecimal total,
		String status, Instant createdAt, Instant confirmedAt, String rejectionReason, long version,
		List<SalesOrderLineView> lines) {
	public SalesOrderView { lines = List.copyOf(lines); }
}
