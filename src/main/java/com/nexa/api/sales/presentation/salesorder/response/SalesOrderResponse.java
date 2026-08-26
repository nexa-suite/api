package com.nexa.api.sales.presentation.salesorder.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;

public record SalesOrderResponse(String id, String number, String tenantId, String workspaceId, String clientAccountId,
		String createdByMembershipId, String buyerMembershipId, String sourcePurchaseRequestId, PurchaseRequestPriority priority,
		LocalDate requestedDeliveryDate, String deliverySnapshot, PaymentOption paymentOption, String notes,
		String currency, BigDecimal total, String status, Instant createdAt, Instant updatedAt, Instant confirmedAt,
		Instant rejectedAt, Instant cancelledAt, String rejectionReason, long version, List<SalesOrderLineResponse> lines,
		String originType, String commercialCommitmentId) {
		public SalesOrderResponse(String id, String number, String tenantId, String workspaceId, String clientAccountId,
				String createdByMembershipId, String buyerMembershipId, String sourcePurchaseRequestId, PurchaseRequestPriority priority,
				LocalDate requestedDeliveryDate, String deliverySnapshot, PaymentOption paymentOption, String notes,
				String currency, BigDecimal total, String status, Instant createdAt, Instant updatedAt, Instant confirmedAt,
				Instant rejectedAt, Instant cancelledAt, String rejectionReason, long version, List<SalesOrderLineResponse> lines) {
			this(id, number, tenantId, workspaceId, clientAccountId, createdByMembershipId, buyerMembershipId, sourcePurchaseRequestId,
					priority, requestedDeliveryDate, deliverySnapshot, paymentOption, notes, currency, total, status, createdAt, updatedAt,
					confirmedAt, rejectedAt, cancelledAt, rejectionReason, version, lines, null, null);
		}
		public SalesOrderResponse { lines = List.copyOf(lines); }
	}
