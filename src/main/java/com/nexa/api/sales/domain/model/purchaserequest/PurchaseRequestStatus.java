package com.nexa.api.sales.domain.model.purchaserequest;

public enum PurchaseRequestStatus {
	DRAFT, SUBMITTED, IN_REVIEW, NEEDS_ADJUSTMENT, APPROVED, REJECTED, CANCELLED, CONVERTED_TO_ORDER;
	public boolean isTerminal() { return this == APPROVED || this == REJECTED || this == CANCELLED || this == CONVERTED_TO_ORDER; }
}
