package com.nexa.api.salescommitment.presentation.purchaserequest.response;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

public record PurchaseRequestDetailResponse(String id, String code, String clientAccountId, String buyerMembershipId,
		String status, String priority, LocalDate requestedDeliveryDate, String deliveryProfileSnapshot,
			String paymentOption, String comment, String reviewNote, List<PurchaseRequestLineResponse> lines, long version,
			Instant expiresAt) {
		public PurchaseRequestDetailResponse(String id, String code, String clientAccountId, String buyerMembershipId,
				String status, String priority, LocalDate requestedDeliveryDate, String deliveryProfileSnapshot,
				String paymentOption, String comment, String reviewNote, List<PurchaseRequestLineResponse> lines, long version) {
			this(id, code, clientAccountId, buyerMembershipId, status, priority, requestedDeliveryDate,
					deliveryProfileSnapshot, paymentOption, comment, reviewNote, lines, version, null);
		}
		public PurchaseRequestDetailResponse { lines = List.copyOf(lines); }
	}
