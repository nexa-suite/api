package com.nexa.api.salescommitment.application.purchaserequest.model;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public record PurchaseRequestView(String id, String code, String clientAccountId, String buyerMembershipId,
		String status, String priority, LocalDate requestedDeliveryDate, String deliveryProfileSnapshot,
		String paymentOption, String comment, String reviewNote, List<PurchaseRequestLineView> lines, long version,
		Instant expiresAt) {
	public PurchaseRequestView(String id, String code, String clientAccountId, String buyerMembershipId,
			String status, String priority, LocalDate requestedDeliveryDate, String deliveryProfileSnapshot,
			String paymentOption, String comment, String reviewNote, List<PurchaseRequestLineView> lines, long version) {
		this(id, code, clientAccountId, buyerMembershipId, status, priority, requestedDeliveryDate,
				deliveryProfileSnapshot, paymentOption, comment, reviewNote, lines, version, null);
	}
		public PurchaseRequestView { lines = List.copyOf(lines); }
	}
