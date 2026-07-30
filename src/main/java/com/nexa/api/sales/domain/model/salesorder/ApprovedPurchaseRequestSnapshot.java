package com.nexa.api.sales.domain.model.salesorder;

import com.nexa.api.sales.domain.model.clientaccount.ClientAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.sales.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record ApprovedPurchaseRequestSnapshot(TenantId tenantId, WorkspaceId workspaceId, ClientAccountId clientAccountId,
		BuyerMembershipId buyerMembershipId, PurchaseRequestId purchaseRequestId, List<SalesOrderLine> lines,
		PurchaseRequestPriority priority, LocalDate requestedDeliveryDate, String deliverySnapshot,
		PaymentOption paymentOption, String notes, String currency, BigDecimal totalSnapshot) {
	public ApprovedPurchaseRequestSnapshot(TenantId tenantId, WorkspaceId workspaceId, ClientAccountId clientAccountId,
			PurchaseRequestId purchaseRequestId, List<SalesOrderLine> lines, BigDecimal totalSnapshot) {
		this(tenantId, workspaceId, clientAccountId, new BuyerMembershipId(java.util.UUID.randomUUID()), purchaseRequestId,
				lines, PurchaseRequestPriority.NORMAL, null, null, null, null,
				lines.isEmpty() ? null : lines.getFirst().unitPriceCurrency(), totalSnapshot);
	}
	public ApprovedPurchaseRequestSnapshot {
		tenantId = Objects.requireNonNull(tenantId); workspaceId = Objects.requireNonNull(workspaceId); clientAccountId = Objects.requireNonNull(clientAccountId);
		buyerMembershipId = Objects.requireNonNull(buyerMembershipId); purchaseRequestId = Objects.requireNonNull(purchaseRequestId); lines = List.copyOf(lines);
		priority = priority == null ? PurchaseRequestPriority.NORMAL : priority;
		currency = Objects.requireNonNull(currency).trim().toUpperCase(java.util.Locale.ROOT); totalSnapshot = Objects.requireNonNull(totalSnapshot);
		if (lines.isEmpty() || totalSnapshot.signum() < 0 || !currency.matches("[A-Z]{3}")) throw new SalesOrderInvariantViolation("Approved purchase request snapshot is incomplete");
		String normalizedCurrency = currency;
		if (lines.stream().anyMatch(line -> !normalizedCurrency.equals(line.unitPriceCurrency()))) throw new SalesOrderInvariantViolation("Sales order currency must be consistent");
	}
}
