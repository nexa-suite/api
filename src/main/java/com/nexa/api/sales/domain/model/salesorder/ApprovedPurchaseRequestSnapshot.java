package com.nexa.api.sales.domain.model.salesorder;

import com.nexa.api.sales.domain.model.clientaccount.ClientAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record ApprovedPurchaseRequestSnapshot(TenantId tenantId, WorkspaceId workspaceId, ClientAccountId clientAccountId,
		PurchaseRequestId purchaseRequestId, List<SalesOrderLine> lines, BigDecimal totalSnapshot) {
	public ApprovedPurchaseRequestSnapshot {
		tenantId = Objects.requireNonNull(tenantId); workspaceId = Objects.requireNonNull(workspaceId); clientAccountId = Objects.requireNonNull(clientAccountId); purchaseRequestId = Objects.requireNonNull(purchaseRequestId); lines = List.copyOf(lines); totalSnapshot = Objects.requireNonNull(totalSnapshot);
		if (lines.isEmpty() || totalSnapshot.signum() < 0) throw new SalesOrderInvariantViolation("Approved purchase request snapshot is incomplete");
	}
}
