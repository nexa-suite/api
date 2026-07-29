package com.nexa.api.sales.application.model;

import java.time.LocalDate;
import java.util.List;

public record PurchaseRequestView(String id, String code, String clientAccountId, String buyerMembershipId,
		String status, String priority, LocalDate requestedDeliveryDate, String deliveryProfileSnapshot,
		String paymentOption, String comment, String reviewNote, List<PurchaseRequestLineView> lines, long version) {
	public PurchaseRequestView { lines = List.copyOf(lines); }
}
