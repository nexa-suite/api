package com.nexa.api.sales.presentation.purchaserequest.response;

import java.time.LocalDate;
import java.util.List;

public record PurchaseRequestDetailResponse(String id, String code, String clientAccountId, String buyerMembershipId,
		String status, String priority, LocalDate requestedDeliveryDate, String deliveryProfileSnapshot,
		String paymentOption, String comment, String reviewNote, List<PurchaseRequestLineResponse> lines, long version) {
	public PurchaseRequestDetailResponse { lines = List.copyOf(lines); }
}
