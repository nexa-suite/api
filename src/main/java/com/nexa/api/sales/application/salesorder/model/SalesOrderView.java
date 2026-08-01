package com.nexa.api.sales.application.salesorder.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;

public record SalesOrderView(String id, String number, String tenantId, String workspaceId, String clientAccountId,
		String createdByMembershipId, String buyerMembershipId, String sourcePurchaseRequestId, PurchaseRequestPriority priority,
		LocalDate requestedDeliveryDate, String deliverySnapshot, PaymentOption paymentOption, String notes,
		String currency, BigDecimal total, String status, Instant createdAt, Instant updatedAt, Instant confirmedAt,
		Instant rejectedAt, Instant cancelledAt, String rejectionReason, long version,
		List<SalesOrderLineView> lines) {
	public SalesOrderView { lines = List.copyOf(lines); }
}
